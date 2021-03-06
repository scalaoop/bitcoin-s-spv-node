package org.bitcoins.spvnode

import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.protocol.NetworkElement
import org.bitcoins.core.util.Factory
import org.bitcoins.spvnode.headers.NetworkHeader
import org.bitcoins.spvnode.messages.NetworkPayload
import org.bitcoins.spvnode.serializers.RawNetworkMessageSerializer

/**
  * Created by chris on 6/10/16.
  * Represents an entire p2p network message in bitcoins
  */
sealed trait NetworkMessage extends NetworkElement {
  def header : NetworkHeader
  def payload : NetworkPayload
  override def hex = RawNetworkMessageSerializer.write(this)
}


object NetworkMessage extends Factory[NetworkMessage] {
  private case class NetworkMessageImpl(header : NetworkHeader, payload : NetworkPayload) extends NetworkMessage


  def fromBytes(bytes : Seq[Byte]) : NetworkMessage = RawNetworkMessageSerializer.read(bytes)
  /**
    * Creates a network message from it's [[NetworkHeader]] and [[NetworkPayload]]
    * @param header the [[NetworkHeader]] which is being sent across the network
    * @param payload the [[NetworkPayload]] which contains the information being sent across the network
    * @return
    */
  def apply(header : NetworkHeader, payload : NetworkPayload) : NetworkMessage = {
    NetworkMessageImpl(header,payload)
  }

  /**
    * Creates a [[NetworkMessage]] out of it's [[NetworkPayload]]
    * @param network the [[NetworkParameters]] indicating the network which the message is going to be sent on
    * @param payload the payload that needs to be sent across the network
    * @return
    */
  def apply(network : NetworkParameters, payload : NetworkPayload) : NetworkMessage = {
    val header = NetworkHeader(network, payload)
    NetworkMessage(header,payload)
  }
}
