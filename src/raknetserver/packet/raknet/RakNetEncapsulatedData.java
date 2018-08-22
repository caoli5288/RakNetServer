package raknetserver.packet.raknet;

import io.netty.buffer.ByteBuf;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.RakNetDataSerializer;

import java.util.ArrayList;

public class RakNetEncapsulatedData implements RakNetPacket {

	private final ArrayList<EncapsulatedPacket> packets = new ArrayList<>();
	private int id;
	private int skip;
	private boolean ack;

	public RakNetEncapsulatedData() {
	}

	public RakNetEncapsulatedData(EncapsulatedPacket epacket) {
		packets.add(epacket);
	}

    public boolean isAck() {
		return ack;
	}

	public void setAck(boolean ack) {
		this.ack = ack;
	}

	public int skip() {
		skip++;
		return skip;
	}

	@Override
	public void decode(ByteBuf buf) {
		id = buf.readUnsignedMediumLE();
		while (buf.isReadable()) {
			EncapsulatedPacket packet = new EncapsulatedPacket();
			packet.decode(buf);
			packets.add(packet);
		}
	}

	@Override
	public void encode(ByteBuf buf) {
		buf.writeMediumLE(id);
		for (EncapsulatedPacket packet : packets) {
			packet.encode(buf);
		}
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		skip = 0;
		this.id = id;
	}

	public ArrayList<EncapsulatedPacket> getPackets() {
		return packets;
	}

}
