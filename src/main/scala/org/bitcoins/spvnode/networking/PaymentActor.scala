package org.bitcoins.spvnode.networking

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorContext, ActorRef, ActorRefFactory, Props}
import akka.event.LoggingReceive
import akka.io.Tcp
import org.bitcoins.core.crypto.{DoubleSha256Digest, Sha256Hash160Digest}
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.Address
import org.bitcoins.core.util.{BitcoinSLogger, BitcoinSUtil}
import org.bitcoins.spvnode.NetworkMessage
import org.bitcoins.spvnode.block.MerkleBlock
import org.bitcoins.spvnode.bloom.{BloomFilter, BloomUpdateNone}
import org.bitcoins.spvnode.constant.Constants
import org.bitcoins.spvnode.messages.data.{GetDataMessage, Inventory, InventoryMessage}
import org.bitcoins.spvnode.messages._
import org.bitcoins.spvnode.messages.control.FilterLoadMessage
import org.bitcoins.spvnode.util.BitcoinSpvNodeUtil

/**
  * Created by chris on 8/30/16.
  * Responsible for checking if a payment to a address was made
  * Verifying that the transaction that made the payment was included
  * inside of a block on the blockchain
  *
  * 1.) Creates a bloom filter
  * 2.) Sends the bloom filter to a node on the network
  * 3.) Nodes matches the bloom filter
  * 4.) When another block is announced on the network, we send a MsgMerkleBlock
  * to our peer on the network to see if the tx was included on that block
  * 5.) If it was, send the actor that that requested this [[PaymentActor.SuccessfulPayment]] message back
  */
sealed trait PaymentActor extends Actor with BitcoinSLogger {

  def receive = LoggingReceive {
    case hash: Sha256Hash160Digest =>
      paymentToHash(hash)
    case address: Address =>
      self.forward(address.hash)
  }

  def paymentToHash(hash: Sha256Hash160Digest) = {
    val bloomFilter = BloomFilter(10,0.0001,UInt32.zero,BloomUpdateNone).insert(hash)
    val filterLoadMsg = FilterLoadMessage(bloomFilter)
    val peerMsgHandler = PeerMessageHandler(context)
    val bloomFilterNetworkMsg = NetworkMessage(Constants.networkParameters,filterLoadMsg)
    peerMsgHandler ! bloomFilterNetworkMsg
    logger.debug("Switching to awaitTransactionInventoryMessage")
    context.become(awaitTransactionInventoryMessage(hash, peerMsgHandler))
  }

  def awaitTransactionInventoryMessage(hash: Sha256Hash160Digest, peerMessageHandler: ActorRef): Receive = LoggingReceive {
    case invMsg: InventoryMessage =>
      //txs are broadcast by nodes on the network when they are seen by a node
      //filter out the txs we do not care about
      val txInventories = invMsg.inventories.filter(_.typeIdentifier == MsgTx)
      //fire off individual GetDataMessages for the txids we received
      for { txInv <- txInventories } yield {
        val inventory = GetDataMessage(txInv)
        peerMessageHandler ! inventory
      }
      logger.debug("Switching to awaitTransactionInventoryMessage")
      context.become(awaitTransactionGetDataMessage(hash,peerMessageHandler))
  }

  def awaitTransactionGetDataMessage(hash: Sha256Hash160Digest, peerMessageHandler: ActorRef): Receive = LoggingReceive {
    case txMsg : TransactionMessage =>
      //check to see if any of the outputs on this tx match our hash
      val outputs = txMsg.transaction.outputs.filter(o => o.scriptPubKey.asm.filter(_.bytes == hash.bytes).nonEmpty)

      if (outputs.nonEmpty) {
        logger.debug("matched transaction inside of awaitTransactionGetDataMsg: " + txMsg.transaction.hex)
        logger.debug("Matched txid: " + txMsg.transaction.txId.hex)
        logger.debug("Switching to awaitBlockAnnouncement")
        context.become(awaitBlockAnnouncement(hash,txMsg.transaction.txId, peerMessageHandler))
      }
      //otherwise we do nothing and wait for another transaction message
  }

  def awaitBlockAnnouncement(hash: Sha256Hash160Digest, txId: DoubleSha256Digest, peerMessageHandler: ActorRef): Receive = LoggingReceive {
    case invMsg: InventoryMessage =>
      val blockHashes = invMsg.inventories.filter(_.typeIdentifier == MsgBlock).map(_.hash)
      if (blockHashes.nonEmpty) {
        //construct a merkle block message to verify that the txIds was in the block
        val merkleBlockInventory = Inventory(MsgFilteredBlock,blockHashes.head)
        val getDataMsg = GetDataMessage(merkleBlockInventory)
        val getDataNetworkMessage = NetworkMessage(Constants.networkParameters,getDataMsg)
        peerMessageHandler ! getDataNetworkMessage
        logger.debug("Switching to awaitMerkleBlockMessage")
        context.become(awaitMerkleBlockMessage(hash,txId,blockHashes, peerMessageHandler))
      }
      //else do nothing and wait for another block announcement

  }

  def awaitMerkleBlockMessage(hash: Sha256Hash160Digest, txId: DoubleSha256Digest, blockHashes: Seq[DoubleSha256Digest],
                              peerMessageHandler: ActorRef): Receive = LoggingReceive {
    case merkleBlockMsg: MerkleBlockMessage =>
      val result = merkleBlockMsg.merkleBlock.partialMerkleTree.extractMatches.contains(txId)
      if (result) {
        val successfulPayment = PaymentActor.SuccessfulPayment(hash,txId,blockHashes,merkleBlockMsg.merkleBlock)
        logger.info("Receive successful payment: " + successfulPayment)
        context.parent ! successfulPayment
      } else context.parent ! PaymentActor.FailedPayment(hash)
      peerMessageHandler ! Tcp.Close
      context.stop(self)
  }
}

object PaymentActor {
  private case class PaymentActorImpl() extends PaymentActor

  def props = Props(classOf[PaymentActorImpl])

  def apply(context: ActorRefFactory): ActorRef = context.actorOf(props, BitcoinSpvNodeUtil.createActorName(this.getClass))

  sealed trait PaymentActorMessage
  case class SuccessfulPayment(hash:Sha256Hash160Digest, txId: DoubleSha256Digest,
                               blockHash: Seq[DoubleSha256Digest], merkleBlock: MerkleBlock) extends PaymentActorMessage

  case class FailedPayment(hash: Sha256Hash160Digest) extends PaymentActorMessage
}
