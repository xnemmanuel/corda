package net.corda.core.internal

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.NonEmptySet
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.sequence
import net.corda.core.utilities.unwrap
import net.corda.testing.contracts.DummyContract
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.internal.DUMMY_CONTRACTS_CORDAPP
import net.corda.testing.node.internal.enclosedCordapp
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// DOCSTART 3
class ResolveTransactionsFlowTest {
    private lateinit var mockNet: MockNetwork
    private lateinit var notaryNode: StartedMockNode
    private lateinit var megaCorpNode: StartedMockNode
    private lateinit var miniCorpNode: StartedMockNode
    private lateinit var megaCorp: Party
    private lateinit var miniCorp: Party
    private lateinit var notary: Party

    @Before
    fun setup() {
        mockNet = MockNetwork(MockNetworkParameters(cordappsForAllNodes = listOf(DUMMY_CONTRACTS_CORDAPP, enclosedCordapp())))
        notaryNode = mockNet.defaultNotaryNode
        megaCorpNode = mockNet.createPartyNode(CordaX500Name("MegaCorp", "London", "GB"))
        miniCorpNode = mockNet.createPartyNode(CordaX500Name("MiniCorp", "London", "GB"))
        notary = mockNet.defaultNotaryIdentity
        megaCorp = megaCorpNode.info.singleIdentity()
        miniCorp = miniCorpNode.info.singleIdentity()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }
    // DOCEND 3

    // DOCSTART 1
    @Test
    fun `resolve from two hashes`() {
        val (stx1, stx2) = makeTransactions()
        val p = TestFlow(setOf(stx2.id), megaCorp)
        val future = miniCorpNode.startFlow(p)
        mockNet.runNetwork()
        future.getOrThrow()
        miniCorpNode.transaction {
            assertEquals(stx1, miniCorpNode.services.validatedTransactions.getTransaction(stx1.id))
            assertEquals(stx2, miniCorpNode.services.validatedTransactions.getTransaction(stx2.id))
        }
    }
    // DOCEND 1

    @Test
    fun `dependency with an error`() {
        val stx = makeTransactions(signFirstTX = false).second
        val p = TestFlow(setOf(stx.id), megaCorp)
        val future = miniCorpNode.startFlow(p)
        mockNet.runNetwork()
        assertFailsWith(SignedTransaction.SignaturesMissingException::class) { future.getOrThrow() }
    }

    @Test
    fun `resolve from a signed transaction`() {
        val (stx1, stx2) = makeTransactions()
        val p = TestFlow(stx2, megaCorp)
        val future = miniCorpNode.startFlow(p)
        mockNet.runNetwork()
        future.getOrThrow()
        miniCorpNode.transaction {
            assertEquals(stx1, miniCorpNode.services.validatedTransactions.getTransaction(stx1.id))
            // But stx2 wasn't inserted, just stx1.
            assertNull(miniCorpNode.services.validatedTransactions.getTransaction(stx2.id))
        }
    }

    @Test
    fun `denial of service check`() {
        // Chain lots of txns together.
        val stx2 = makeTransactions().second
        val count = 50
        var cursor = stx2
        repeat(count) {
            val builder = DummyContract.move(cursor.tx.outRef(0), miniCorp)
            val stx = megaCorpNode.services.signInitialTransaction(builder)
            megaCorpNode.transaction {
                megaCorpNode.services.recordTransactions(stx)
            }
            cursor = stx
        }
        val p = TestFlow(setOf(cursor.id), megaCorp, 40)
        val future = miniCorpNode.startFlow(p)
        mockNet.runNetwork()
        assertFailsWith<ResolveTransactionsFlow.ExcessivelyLargeTransactionGraph> { future.getOrThrow() }
    }

    @Test
    fun `triangle of transactions resolves fine`() {
        val stx1 = makeTransactions().first

        val stx2 = DummyContract.move(stx1.tx.outRef(0), miniCorp).let { builder ->
            val ptx = megaCorpNode.services.signInitialTransaction(builder)
            notaryNode.services.addSignature(ptx, notary.owningKey)
        }

        megaCorpNode.transaction {
            megaCorpNode.services.recordTransactions(stx2)
        }

        val stx3 = DummyContract.move(listOf(stx1.tx.outRef(0), stx2.tx.outRef(0)), miniCorp).let { builder ->
            val ptx = megaCorpNode.services.signInitialTransaction(builder)
            notaryNode.services.addSignature(ptx, notary.owningKey)
        }

        megaCorpNode.transaction {
            megaCorpNode.services.recordTransactions(stx3)
        }

        val p = TestFlow(setOf(stx3.id), megaCorp)
        val future = miniCorpNode.startFlow(p)
        mockNet.runNetwork()
        future.getOrThrow()
    }

    // todo - use this to test
    @Test
    fun attachment() {
        fun makeJar(): InputStream {
            val bs = ByteArrayOutputStream()
            val jar = JarOutputStream(bs)
            jar.putNextEntry(JarEntry("TEST"))
            jar.write("Some test file".toByteArray())
            jar.closeEntry()
            jar.close()
            return bs.toByteArray().sequence().open()
        }
        // TODO: this operation should not require an explicit transaction
        val id = megaCorpNode.transaction {
            megaCorpNode.services.attachments.importAttachment(makeJar(), "TestDSL", null)
        }
        val stx2 = makeTransactions(withAttachment = id).second
        val p = TestFlow(stx2, megaCorp)
        val future = miniCorpNode.startFlow(p)
        mockNet.runNetwork()
        future.getOrThrow()

        // TODO: this operation should not require an explicit transaction
        miniCorpNode.transaction {
            assertNotNull(miniCorpNode.services.attachments.openAttachment(id))
        }
    }

    @Test
    fun `Requesting a transaction while having the right to see it succeeds`() {
        val (_, stx2) = makeTransactions()
        val p = TestNoRightsVendingFlow(miniCorp, toVend = stx2, toRequest = stx2)
        val future = megaCorpNode.startFlow(p)
        mockNet.runNetwork()
        future.getOrThrow()
    }

    @Test
    fun `Requesting a transaction without having the right to see it results in exception`() {
        val (_, stx2) = makeTransactions()
        val (_, stx3) = makeTransactions()
        val p = TestNoRightsVendingFlow(miniCorp, toVend = stx2, toRequest = stx3)
        val future = megaCorpNode.startFlow(p)
        mockNet.runNetwork()
        assertFailsWith<FetchDataFlow.IllegalTransactionRequest> { future.getOrThrow() }
    }

    @Test
    fun `Requesting a transaction twice results in exception`() {
        val (_, stx2) = makeTransactions()
        val p = TestResolveTwiceVendingFlow(miniCorp, stx2)
        val future = megaCorpNode.startFlow(p)
        mockNet.runNetwork()
        assertFailsWith<FetchDataFlow.IllegalTransactionRequest> { future.getOrThrow() }
    }

    // DOCSTART 2
    private fun makeTransactions(signFirstTX: Boolean = true, withAttachment: SecureHash? = null): Pair<SignedTransaction, SignedTransaction> {
        // Make a chain of custody of dummy states and insert into node A.
        val dummy1: SignedTransaction = DummyContract.generateInitial(0, notary, megaCorp.ref(1)).let {
            if (withAttachment != null)
                it.addAttachment(withAttachment)
            when (signFirstTX) {
                true -> {
                    val ptx = megaCorpNode.services.signInitialTransaction(it)
                    notaryNode.services.addSignature(ptx, notary.owningKey)
                }
                false -> {
                    notaryNode.services.signInitialTransaction(it, notary.owningKey)
                }
            }
        }
        megaCorpNode.transaction {
            megaCorpNode.services.recordTransactions(dummy1)
        }
        val dummy2: SignedTransaction = DummyContract.move(dummy1.tx.outRef(0), miniCorp).let {
            val ptx = megaCorpNode.services.signInitialTransaction(it)
            notaryNode.services.addSignature(ptx, notary.owningKey)
        }
        megaCorpNode.transaction {
            megaCorpNode.services.recordTransactions(dummy2)
        }
        return Pair(dummy1, dummy2)
    }
    // DOCEND 2


    @InitiatingFlow
    open class TestFlow(val otherSide: Party, private val resolveTransactionsFlowFactory: (FlowSession) -> ResolveTransactionsFlow, private val txCountLimit: Int? = null) : FlowLogic<Unit>() {
        constructor(txHashes: Set<SecureHash>, otherSide: Party, txCountLimit: Int? = null) : this(otherSide, { ResolveTransactionsFlow(txHashes, it) }, txCountLimit = txCountLimit)
        constructor(stx: SignedTransaction, otherSide: Party) : this(otherSide, { ResolveTransactionsFlow(stx, it) })

        @Suspendable
        override fun call() {
            val session = initiateFlow(otherSide)
            val resolveTransactionsFlow = resolveTransactionsFlowFactory(session)
            txCountLimit?.let { resolveTransactionsFlow.transactionCountLimit = it }
            subFlow(resolveTransactionsFlow)
        }
    }
    @Suppress("unused")
    @InitiatedBy(TestFlow::class)
    class TestResponseFlow(val otherSideSession: FlowSession) : FlowLogic<Void?>() {
        @Suspendable
        override fun call() = subFlow(TestNoSecurityDataVendingFlow(otherSideSession))
    }

    // Used by the no-rights test
    @InitiatingFlow
    private class TestNoRightsVendingFlow(val otherSide: Party, val toVend: SignedTransaction, val toRequest: SignedTransaction) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val session = initiateFlow(otherSide)
            session.send(toRequest)
            subFlow(DataVendingFlow(session, toVend))
        }
    }
    @Suppress("unused")
    @InitiatedBy(TestNoRightsVendingFlow::class)
    private open class TestResponseResolveNoRightsFlow(val otherSideSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val noRightsTx = otherSideSession.receive<SignedTransaction>().unwrap { it }
            otherSideSession.receive<Any>().unwrap { it }
            otherSideSession.sendAndReceive<Any>(FetchDataFlow.Request.Data(NonEmptySet.of(noRightsTx.inputs.first().txhash), FetchDataFlow.DataType.TRANSACTION)).unwrap { it }
            otherSideSession.send(FetchDataFlow.Request.End)
        }
    }

    //Used by the resolve twice test
    @InitiatingFlow
    private class TestResolveTwiceVendingFlow(val otherSide: Party, val tx: SignedTransaction) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val session = initiateFlow(otherSide)
            subFlow(DataVendingFlow(session, tx))
        }
    }
    @Suppress("unused")
    @InitiatedBy(TestResolveTwiceVendingFlow::class)
    private open class TestResponseResolveTwiceFlow(val otherSideSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val tx = otherSideSession.receive<SignedTransaction>().unwrap { it }
            val parent1 = tx.inputs.first().txhash
            otherSideSession.sendAndReceive<Any>(FetchDataFlow.Request.Data(NonEmptySet.of(parent1), FetchDataFlow.DataType.TRANSACTION)).unwrap { it }
            otherSideSession.sendAndReceive<Any>(FetchDataFlow.Request.Data(NonEmptySet.of(parent1), FetchDataFlow.DataType.TRANSACTION)).unwrap { it }
            otherSideSession.send(FetchDataFlow.Request.End)
        }
    }
}
