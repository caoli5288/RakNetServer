package raknetserver.packet.raknet;

import io.netty.buffer.ByteBuf;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.RakNetDataSerializer;
import raknetserver.utils.Constants;
import raknetserver.utils.Utils;

import java.util.ArrayList;

public class RakNetEncapsulatedData implements RakNetPacket {

	private int seqId;
	private final ArrayList<EncapsulatedPacket> packets = new ArrayList<EncapsulatedPacket>();

	private int rto;
	private long sendtime;
	private int sendcount;

	public RakNetEncapsulatedData() {
	}

	public RakNetEncapsulatedData(EncapsulatedPacket epacket) {
		packets.add(epacket);
	}

	public boolean isRTOTimeout(long now) {
		return sendtime + rto < now;
	}

	public int getRTT() {
		return ((int) (System.currentTimeMillis() - sendtime));
	}

	public int sendcount() {
		return sendcount;
	}

	public void updateRTO(int rxrto) {
		if (sendcount++ == 0) {
			rto = rxrto;
		} else {
			rto = Utils.bound(rto * 2, rxrto, Constants.RTO_MAX);
		}
		sendtime = System.currentTimeMillis();
	}

	public int getRTO() {
		return rto;
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
