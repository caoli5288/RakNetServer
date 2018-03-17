package raknetserver.packet.raknet;

import io.netty.buffer.ByteBuf;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.RakNetDataSerializer;

import java.util.ArrayList;

public class RakNetEncapsulatedData implements RakNetPacket {

	private int seqId;
	private final ArrayList<EncapsulatedPacket> packets = new ArrayList<EncapsulatedPacket>();

	private int rto;
	private long sendtime;

	public RakNetEncapsulatedData() {
	}

	public RakNetEncapsulatedData(EncapsulatedPacket epacket) {
		packets.add(epacket);
	}

	public boolean isRTOTimeout() {
		return sendtime + rto < System.currentTimeMillis();
	}

	public int getRTT() {
		return ((int) (System.currentTimeMillis() - sendtime));
	}

	public void updateRTO(int rxrto) {
		if (sendtime == 0) {
			rto = rxrto;
		} else {
			rto = Math.max(rto * 2, rxrto);
		}
		sendtime = System.currentTimeMillis();
	}

	@Override
	public void decode(ByteBuf buf) {
		seqId = RakNetDataSerializer.readTriad(buf);
		while (buf.isReadable()) {
			EncapsulatedPacket packet = new EncapsulatedPacket();
			packet.decode(buf);
			packets.add(packet);
		}
	}

	@Override
	public void encode(ByteBuf buf) {
		RakNetDataSerializer.writeTriad(buf, seqId);
		for (EncapsulatedPacket packet : packets) {
			packet.encode(buf);
		}
	}

	public int getSeqId() {
		return seqId;
	}

	public void setSeqId(int seqId) {
		this.seqId = seqId;
	}

	public ArrayList<EncapsulatedPacket> getPackets() {
		return packets;
	}

}
