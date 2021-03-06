package org.bitcoins.spvnode.messages.control

import org.bitcoins.core.number.UInt64
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.util.Factory
import org.bitcoins.spvnode.messages.RejectMessage
import org.bitcoins.spvnode.serializers.messages.control.RawRejectMessageSerializer

/**
  * Created by chris on 8/31/16.
  * Factory object for
  * [[https://bitcoin.org/en/developer-reference#reject]]
  */
object RejectMessage extends Factory[RejectMessage] {
  private case class RejectMessageImpl(messageSize: CompactSizeUInt, message: String, code: Char, reasonSize: CompactSizeUInt,
                                       reason: String, extra: Seq[Byte]) extends RejectMessage

  def apply(messageSize: CompactSizeUInt, message: String, code: Char, reasonSize: CompactSizeUInt,
            reason: String, extra: Seq[Byte]): RejectMessage = {
    RejectMessageImpl(messageSize, message, code, reasonSize, reason, extra)
  }

  def fromBytes(bytes: Seq[Byte]): RejectMessage = RawRejectMessageSerializer.read(bytes)

  def apply(message: String, code: Char,
            reason: String, extra: Seq[Byte]): RejectMessage = {
    val messageSize: CompactSizeUInt = CompactSizeUInt(UInt64(message.size))
    val reasonSize: CompactSizeUInt = CompactSizeUInt(UInt64(reason.size))
    RejectMessage(messageSize, message, code, reasonSize, reason, extra)
  }
}

