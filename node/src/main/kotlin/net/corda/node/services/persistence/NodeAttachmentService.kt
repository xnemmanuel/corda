package net.corda.node.services.persistence

import com.codahale.metrics.MetricRegistry
import com.github.benmanes.caffeine.cache.Weigher
import com.google.common.hash.HashCode
import com.google.common.hash.Hashing
import com.google.common.hash.HashingInputStream
import com.google.common.io.CountingInputStream
import net.corda.core.CordaRuntimeException
import net.corda.core.contracts.Attachment
import net.corda.core.contracts.ContractAttachment
import net.corda.core.contracts.ContractClassName
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.internal.*
import net.corda.core.internal.Version
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_CONTRACT_VERSION
import net.corda.core.internal.cordapp.CordappImpl.Companion.DEFAULT_CORDAPP_VERSION
import net.corda.core.node.ServicesForResolution
import net.corda.core.node.services.AttachmentId
import net.corda.core.node.services.vault.AttachmentQueryCriteria
import net.corda.core.node.services.vault.AttachmentSort
import net.corda.core.node.services.vault.Builder
import net.corda.core.node.services.vault.Sort
import net.corda.core.serialization.*
import net.corda.core.utilities.contextLogger
import net.corda.node.services.vault.HibernateAttachmentQueryCriteriaParser
import net.corda.node.utilities.InfrequentlyMutatedCache
import net.corda.node.utilities.NonInvalidatingCache
import net.corda.node.utilities.NonInvalidatingWeightBasedCache
import net.corda.nodeapi.exceptions.DuplicateAttachmentException
import net.corda.nodeapi.exceptions.DuplicateContractClassException
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.NODE_DATABASE_PREFIX
import net.corda.nodeapi.internal.persistence.currentDBSession
import net.corda.nodeapi.internal.withContractsInJar
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Paths
import java.security.PublicKey
import java.time.Instant
import java.util.*
import java.util.jar.JarInputStream
import javax.annotation.concurrent.ThreadSafe
import javax.persistence.*

/**
 * Stores attachments using Hibernate to database.
 */
@ThreadSafe
class NodeAttachmentService(
        metrics: MetricRegistry,
        cacheFactory: NamedCacheFactory,
        private val database: CordaPersistence,
        val devMode: Boolean
) : AttachmentStorageInternal, SingletonSerializeAsToken() {
    constructor(metrics: MetricRegistry,
                cacheFactory: NamedCacheFactory,
                database: CordaPersistence) : this(metrics, cacheFactory, database, false)

    // This is to break the circular dependency.
    lateinit var servicesForResolution: ServicesForResolution

    companion object {
        private val log = contextLogger()

        private val PRIVILEGED_UPLOADERS = listOf(DEPLOYED_CORDAPP_UPLOADER, RPC_UPLOADER, P2P_UPLOADER, UNKNOWN_UPLOADER)

        // Just iterate over the entries with verification enabled: should be good enough to catch mistakes.
        // Note that JarInputStream won't throw any kind of error at all if the file stream is in fact not
        // a ZIP! It'll just pretend it's an empty archive, which is kind of stupid but that's how it works.
        // So we have to check to ensure we found at least one item.
        private fun checkIsAValidJAR(stream: InputStream) {
            val jar = JarInputStream(stream, true)
            var count = 0
            while (true) {
                val cursor = jar.nextJarEntry ?: break
                val entryPath = Paths.get(cursor.name)
                // Security check to stop zips trying to escape their rightful place.
                require(!entryPath.isAbsolute) { "Path $entryPath is absolute" }
                require(entryPath.normalize() == entryPath) { "Path $entryPath is not normalised" }
                require(!('\\' in cursor.name || cursor.name == "." || cursor.name == "..")) { "Bad character in $entryPath" }
                count++
            }
            require(count > 0) { "Stream is either empty or not a JAR/ZIP" }
        }
    }

    @Entity
    @Table(name = "${NODE_DATABASE_PREFIX}attachments", indexes = [(Index(name = "att_id_idx", columnList = "att_id"))])
    class DBAttachment(
            @Id
            @Column(name = "att_id", nullable = false)
            var attId: String,

            @Column(name = "content", nullable = false)
            @Lob
            var content: ByteArray,

            @Column(name = "insertion_date", nullable = false, updatable = false)
            var insertionDate: Instant = Instant.now(),

            @Column(name = "uploader", nullable = true)
            var uploader: String? = null,

            @Column(name = "filename", updatable = false, nullable = true)
            var filename: String? = null,

            @ElementCollection
            @Column(name = "contract_class_name", nullable = false)
            @CollectionTable(name = "${NODE_DATABASE_PREFIX}attachments_contracts", joinColumns = [(JoinColumn(name = "att_id", referencedColumnName = "att_id"))],
                    foreignKey = ForeignKey(name = "FK__ctr_class__attachments"))
            var contractClassNames: List<ContractClassName>? = null,

            @ElementCollection(targetClass = PublicKey::class, fetch = FetchType.EAGER)
            @Column(name = "signer", nullable = false)
            @CollectionTable(name = "${NODE_DATABASE_PREFIX}attachments_signers", joinColumns = [(JoinColumn(name = "att_id", referencedColumnName = "att_id"))],
                    foreignKey = ForeignKey(name = "FK__signers__attachments"))
            var signers: List<PublicKey>? = null,

            // Assumption: only Contract Attachments are versioned, version unknown or value for other attachments other than Contract Attachment defaults to 1
            @Column(name = "version", nullable = false)
            var version: Int = DEFAULT_CORDAPP_VERSION
    )

    @VisibleForTesting
    var checkAttachmentsOnLoad = true

    private val attachmentCount = metrics.counter("Attachments")

    fun start() {
        val session = currentDBSession()
        val criteriaBuilder = session.criteriaBuilder
        val criteriaQuery = criteriaBuilder.createQuery(Long::class.java)
        criteriaQuery.select(criteriaBuilder.count(criteriaQuery.from(NodeAttachmentService.DBAttachment::class.java)))
        val count = session.createQuery(criteriaQuery).singleResult
        attachmentCount.inc(count)
    }

    @CordaSerializable
    class HashMismatchException(val expected: SecureHash, val actual: SecureHash) : CordaRuntimeException("File $expected hashed to $actual: corruption in attachment store?")

    /**
     * Wraps a stream and hashes data as it is read: if the entire stream is consumed, then at the end the hash of
     * the read data is compared to the [expected] hash and [HashMismatchException] is thrown by either [read] or [close]
     * if they didn't match. The goal of this is to detect cases where attachments in the store have been tampered with
     * or corrupted and no longer match their file name. It won't always work: if we read a zip for our own uses and skip
     * around inside it, we haven't read the whole file, so we can't check the hash. But when copying it over the network
     * this will provide an additional safety check against user error.
     */
    @VisibleForTesting
    @CordaSerializable
    class HashCheckingStream(val expected: SecureHash.SHA256,
                             val expectedSize: Int,
                             input: InputStream,
                             private val counter: CountingInputStream = CountingInputStream(input),
                             private val stream: HashingInputStream = HashingInputStream(Hashing.sha256(), counter)) : FilterInputStream(stream) {
        @Throws(IOException::class)
        override fun close() {
            super.close()
            validate()
        }

        // Possibly not used, but implemented anyway to fulfil the [FilterInputStream] contract.
        @Throws(IOException::class)
        override fun read(): Int {
            return super.read().apply {
                if (this == -1) {
                    validate()
                }
            }
        }

        // This is invoked by [InputStreamSerializer], which does NOT close the stream afterwards.
        @Throws(IOException::class)
        override fun read(b: ByteArray?, off: Int, len: Int): Int {
            return super.read(b, off, len).apply {
                if (this == -1) {
                    validate()
                }
            }
        }

        private fun validate() {
            if (counter.count != expectedSize.toLong()) return

            val actual = SecureHash.SHA256(hash.asBytes())
            if (actual != expected)
                throw HashMismatchException(expected, actual)
        }

        private var _hash: HashCode? = null // Backing field for hash property
        private val hash: HashCode
            get() {
                var h = _hash
                return if (h == null) {
                    h = stream.hash()
                    _hash = h
                    h
                } else {
                    h
                }
            }
    }

    private class AttachmentImpl(override val id: SecureHash, dataLoader: () -> ByteArray, private val checkOnLoad: Boolean, uploader: String?) : AbstractAttachment(uploader, dataLoader), SerializeAsToken {
        override fun open(): InputStream {
            val stream = super.open()
            // This is just an optional safety check. If it slows things down too much it can be disabled.
            return if (checkOnLoad && id is SecureHash.SHA256) HashCheckingStream(id, attachmentData.size, stream) else stream
        }

        private class Token(private val id: SecureHash, private val checkOnLoad: Boolean, private val uploader: String?) : SerializationToken {
            override fun fromToken(context: SerializeAsTokenContext) = AttachmentImpl(id, context.attachmentDataLoader(id), checkOnLoad, uploader)
        }

        override fun toToken(context: SerializeAsTokenContext) = Token(id, checkOnLoad, uploader)
    }

    // slightly complex 2 level approach to attachment caching:
    // On the first level we cache attachment contents loaded from the DB by their key. This is a weight based
    // cache (we don't want to waste too  much memory on this) and could be evicted quite aggressively. If we fail
    // to load an attachment from the db, the loader will insert a non present optional - we invalidate this
    // immediately as we definitely want to retry whether the attachment was just delayed.
    // On the second level, we cache Attachment implementations that use the first cache to load their content
    // when required. As these are fairly small, we can cache quite a lot of them, this will make checking
    // repeatedly whether an attachment exists fairly cheap. Here as well, we evict non-existent entries immediately
    // to force a recheck if required.
    // If repeatedly looking for non-existing attachments becomes a performance issue, this is either indicating a
    // a problem somewhere else or this needs to be revisited.

    private val attachmentContentCache = NonInvalidatingWeightBasedCache(
            cacheFactory = cacheFactory,
            name = "NodeAttachmentService_attachmentContent",
            weigher = Weigher<SecureHash, Optional<Pair<Attachment, ByteArray>>> { key, value -> key.size + if (value.isPresent) value.get().second.size else 0 },
            loadFunction = { Optional.ofNullable(loadAttachmentContent(it)) }
    )

    private fun loadAttachmentContent(id: SecureHash): Pair<Attachment, ByteArray>? {
        return database.transaction {
            val attachment = currentDBSession().get(NodeAttachmentService.DBAttachment::class.java, id.toString())
                    ?: return@transaction null
            val attachmentImpl = AttachmentImpl(id, { attachment.content }, checkAttachmentsOnLoad, attachment.uploader).let {
                val contracts = attachment.contractClassNames
                if (contracts != null && contracts.isNotEmpty()) {
                    ContractAttachment.create(it, contracts.first(), contracts.drop(1).toSet(), attachment.uploader, attachment.signers?.toList()
                            ?: emptyList(), attachment.version)
                } else {
                    it
                }
            }
            Pair(attachmentImpl, attachment.content)
        }
    }

    private val attachmentCache = NonInvalidatingCache<SecureHash, Optional<Attachment>>(
            cacheFactory = cacheFactory,
            name = "NodeAttachmentService_attachmentPresence",
            loadFunction = { key -> Optional.ofNullable(createAttachment(key)) })

    private fun createAttachment(key: SecureHash): Attachment? {
        val content = attachmentContentCache.get(key)!!
        if (content.isPresent) {
            return content.get().first
        }
        // If no attachment has been found, we don't want to cache that - it might arrive later.
        attachmentContentCache.invalidate(key)
        return null
    }

    override fun openAttachment(id: SecureHash): Attachment? {
        val attachment = attachmentCache.get(id)!!
        if (attachment.isPresent) {
            return attachment.get()
        }
        attachmentCache.invalidate(id)
        return null
    }

    @Suppress("OverridingDeprecatedMember")
    override fun importAttachment(jar: InputStream): AttachmentId {
        return import(jar, UNKNOWN_UPLOADER, null)
    }

    override fun importAttachment(jar: InputStream, uploader: String, filename: String?): AttachmentId {
        require(uploader !in PRIVILEGED_UPLOADERS) { "$uploader is a reserved uploader token" }
        if (uploader.startsWith("$P2P_UPLOADER:")) {
            // FetchAttachmentsFlow is in core and thus doesn't have access to AttachmentStorageInternal to call
            // privilegedImportAttachment
            require(Thread.currentThread().stackTrace.any { it.className == FetchAttachmentsFlow::class.java.name }) {
                "$P2P_UPLOADER is a reserved uploader token prefix"
            }
        }
        return import(jar, uploader, filename)
    }

    override fun privilegedImportAttachment(jar: InputStream, uploader: String, filename: String?): AttachmentId {
        return import(jar, uploader, filename)
    }

    override fun privilegedImportOrGetAttachment(jar: InputStream, uploader: String, filename: String?): AttachmentId {
        return try {
            import(jar, uploader, filename)
        } catch (faee: java.nio.file.FileAlreadyExistsException) {
            AttachmentId.parse(faee.message!!)
        }
    }

    override fun hasAttachment(attachmentId: AttachmentId): Boolean = database.transaction {
        currentDBSession().find(NodeAttachmentService.DBAttachment::class.java, attachmentId.toString()) != null
    }

    private fun verifyVersionUniquenessForSignedAttachments(contractClassNames: List<ContractClassName>, contractVersion: Int, signers: List<PublicKey>?){
        if (signers != null && signers.isNotEmpty()) {
            contractClassNames.forEach {
                val existingContractsImplementations = queryAttachments(AttachmentQueryCriteria.AttachmentsQueryCriteria(
                        contractClassNamesCondition = Builder.equal(listOf(it)),
                        versionCondition = Builder.equal(contractVersion),
                        uploaderCondition = Builder.`in`(TRUSTED_UPLOADERS),
                        isSignedCondition = Builder.equal(true))
                )
                if (existingContractsImplementations.isNotEmpty()) {
                    throw DuplicateContractClassException(it, contractVersion, existingContractsImplementations.map { it.toString() })
                }
            }
        }
    }

    private fun increaseDefaultVersionIfWhitelistedAttachment(contractClassNames: List<ContractClassName>, contractVersionFromFile: Int, attachmentId : AttachmentId) =
        if (contractVersionFromFile == DEFAULT_CORDAPP_VERSION) {
            val versions = contractClassNames.mapNotNull { servicesForResolution.networkParameters.whitelistedContractImplementations[it]?.indexOf(attachmentId) }.filter { it >= 0 }.map { it + 1 } // +1 as versions starts from 1 not 0
            val max = versions.max()
            if (max != null && max > contractVersionFromFile) {
                val msg = "Updating version of attachment $attachmentId from '$contractVersionFromFile' to '$max'"
                if (versions.toSet().size > 1)
                    log.warn("Several versions based on whitelistedContractImplementations position are available: ${versions.toSet()}. $msg")
                else
                    log.debug(msg)
                max
            } else contractVersionFromFile
        }
        else contractVersionFromFile

    // TODO: PLT-147: The attachment should be randomised to prevent brute force guessing and thus privacy leaks.
    private fun import(jar: InputStream, uploader: String?, filename: String?): AttachmentId {
        return database.transaction {
            withContractsInJar(jar) { contractClassNames, inputStream ->
                require(inputStream !is JarInputStream) { "Input stream must not be a JarInputStream" }

                // Read the file into RAM and then calculate its hash. The attachment must fit into memory.
                // TODO: Switch to a two-phase insert so we can handle attachments larger than RAM.
                // To do this we must pipe stream into the database without knowing its hash, which we will learn only once
                // the insert/upload is complete. We can then query to see if it's a duplicate and if so, erase, and if not
                // set the hash field of the new attachment record.

                val bytes = inputStream.readFully()
                val id = bytes.sha256()
                if (!hasAttachment(id)) {
                    checkIsAValidJAR(bytes.inputStream())
                    val jarSigners = getSigners(bytes)
                    val contractVersion = increaseDefaultVersionIfWhitelistedAttachment(contractClassNames, getVersion(bytes), id)
                    val session = currentDBSession()
                    if (!devMode)
                        verifyVersionUniquenessForSignedAttachments(contractClassNames, contractVersion, jarSigners)
                    val attachment = NodeAttachmentService.DBAttachment(
                            attId = id.toString(),
                            content = bytes,
                            uploader = uploader,
                            filename = filename,
                            contractClassNames = contractClassNames,
                            signers = jarSigners,
                            version = contractVersion
                    )
                    session.save(attachment)
                    attachmentCount.inc()
                    log.info("Stored new attachment: id=$id uploader=$uploader filename=$filename")
                    contractClassNames.forEach { contractsCache.invalidate(it) }
                    return@withContractsInJar id
                }
                if (isUploaderTrusted(uploader)) {
                    val session = currentDBSession()
                    val attachment = session.get(NodeAttachmentService.DBAttachment::class.java, id.toString())
                    // update the `uploader` field (as the existing attachment may have been resolved from a peer)
                    if (attachment.uploader != uploader) {
                        if (!devMode)
                            verifyVersionUniquenessForSignedAttachments(contractClassNames, attachment.version, attachment.signers)
                        attachment.uploader = uploader
                        log.info("Updated attachment $id with uploader $uploader")
                        contractClassNames.forEach { contractsCache.invalidate(it) }
                        loadAttachmentContent(id)?.let { attachmentAndContent ->
                            // TODO: this is racey. ENT-2870
                            attachmentContentCache.put(id, Optional.of(attachmentAndContent))
                            attachmentCache.put(id, Optional.of(attachmentAndContent.first))
                        }
                        return@withContractsInJar id
                    }
                    // If the uploader is the same, throw the exception because the attachment cannot be overridden by the same uploader.
                }
                throw DuplicateAttachmentException(id.toString())
            }
        }
    }

    private fun getSigners(attachmentBytes: ByteArray) =
            JarSignatureCollector.collectSigners(JarInputStream(attachmentBytes.inputStream()))

    private fun getVersion(attachmentBytes: ByteArray) =
            JarInputStream(attachmentBytes.inputStream()).use {
                try {
                    it.manifest?.mainAttributes?.getValue(CORDAPP_CONTRACT_VERSION)?.toInt() ?: DEFAULT_CORDAPP_VERSION
                } catch (e: NumberFormatException) {
                    DEFAULT_CORDAPP_VERSION
                }
            }

    @Suppress("OverridingDeprecatedMember")
    override fun importOrGetAttachment(jar: InputStream): AttachmentId {
        return try {
            import(jar, UNKNOWN_UPLOADER, null)
        } catch (faee: java.nio.file.FileAlreadyExistsException) {
            AttachmentId.parse(faee.message!!)
        }
    }

    override fun queryAttachments(criteria: AttachmentQueryCriteria, sorting: AttachmentSort?): List<AttachmentId> {
        log.info("Attachment query criteria: $criteria, sorting: $sorting")
        return database.transaction {
            val session = currentDBSession()
            val criteriaBuilder = session.criteriaBuilder

            val criteriaQuery = criteriaBuilder.createQuery(DBAttachment::class.java)
            val root = criteriaQuery.from(DBAttachment::class.java)

            val criteriaParser = HibernateAttachmentQueryCriteriaParser(criteriaBuilder, criteriaQuery, root)

            // parse criteria and build where predicates
            criteriaParser.parse(criteria, sorting)

            // prepare query for execution
            val query = session.createQuery(criteriaQuery)

            // execution
            query.resultList.map { AttachmentId.parse(it.attId) }
        }
    }

    // Holds onto a signed and/or unsigned attachment (at least one or the other).
    private data class AttachmentIds(val signed: AttachmentId?, val unsigned: AttachmentId?) {
        init {
            // One of them at least must exist.
            check(signed != null || unsigned != null)
        }

        fun toList(): List<AttachmentId> =
                if (signed != null) {
                    if (unsigned != null) {
                        listOf(signed, unsigned)
                    } else listOf(signed)
                } else listOf(unsigned!!)
    }

    /**
     * This caches contract attachment versions by contract class name.  For each version, we support one signed and one unsigned attachment, since that is allowed.
     *
     * It is correctly invalidated as new attachments are uploaded.
     */
    private val contractsCache = InfrequentlyMutatedCache<ContractClassName, NavigableMap<Version, AttachmentIds>>("NodeAttachmentService_contractAttachmentVersions", cacheFactory)

    private fun getContractAttachmentVersions(contractClassName: String): NavigableMap<Version, AttachmentIds> = contractsCache.get(contractClassName) { name ->
        val attachmentQueryCriteria = AttachmentQueryCriteria.AttachmentsQueryCriteria(contractClassNamesCondition = Builder.equal(listOf(name)),
                versionCondition = Builder.greaterThanOrEqual(0), uploaderCondition = Builder.`in`(TRUSTED_UPLOADERS))
        val attachmentSort = AttachmentSort(listOf(AttachmentSort.AttachmentSortColumn(AttachmentSort.AttachmentSortAttribute.VERSION, Sort.Direction.DESC),
                AttachmentSort.AttachmentSortColumn(AttachmentSort.AttachmentSortAttribute.INSERTION_DATE, Sort.Direction.DESC)))
        database.transaction {
            val session = currentDBSession()
            val criteriaBuilder = session.criteriaBuilder

            val criteriaQuery = criteriaBuilder.createQuery(DBAttachment::class.java)
            val root = criteriaQuery.from(DBAttachment::class.java)

            val criteriaParser = HibernateAttachmentQueryCriteriaParser(criteriaBuilder, criteriaQuery, root)

            // parse criteria and build where predicates
            criteriaParser.parse(attachmentQueryCriteria, attachmentSort)

            // prepare query for execution
            val query = session.createQuery(criteriaQuery)

            // execution
            TreeMap(query.resultList.groupBy { it.version }.map { makeAttachmentIds(it, name) }.toMap())
        }
    }

    private fun makeAttachmentIds(it: Map.Entry<Int, List<DBAttachment>>, contractClassName: String): Pair<Version, AttachmentIds> {
        val signed = it.value.filter { it.signers?.isNotEmpty() ?: false }.map { AttachmentId.parse(it.attId) }
        if (!devMode)
            check (signed.size <= 1) //sanity check
        else
            log.warn("(Dev Mode) Multiple signed attachments ${signed.map { it.toString() }} for contract $contractClassName version '${it.key}'.")
        val unsigned = it.value.filter { it.signers?.isEmpty() ?: true }.map { AttachmentId.parse(it.attId) }
        if (unsigned.size > 1)
            log.warn("Selecting attachment ${unsigned.first()} from duplicated, unsigned attachments ${unsigned.map { it.toString() }} for contract $contractClassName version '${it.key}'.")
        return it.key to AttachmentIds(signed.firstOrNull(), unsigned.firstOrNull())
    }

    override fun getLatestContractAttachments(contractClassName: String, minContractVersion: Int): List<AttachmentId> {
        val versions: NavigableMap<Version, AttachmentIds> = getContractAttachmentVersions(contractClassName)
        val newestAttachmentIds = versions.tailMap(minContractVersion, true)
        val newestSignedAttachment = newestAttachmentIds.values.map { it.signed }.lastOrNull { it != null }
        val newestUnsignedAttachment = newestAttachmentIds.values.map { it.unsigned }.lastOrNull { it != null }
        return if (newestSignedAttachment != null || newestUnsignedAttachment != null)
            AttachmentIds(newestSignedAttachment, newestUnsignedAttachment).toList()
        else
            emptyList()
    }
}