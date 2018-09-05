package raknetserver.packet.raknet;

import io.netty.buffer.ByteBuf;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.utils.Constants;

import java.util.ArrayList;

public class RakNetEncapsulatedData implements RakNetPacket {

	private final ArrayList<EncapsulatedPacket> packets = new ArrayList<>();
	private int id;
	private int skip;
	private boolean ack;

	private int rto;
	private long sendTime;
	private int snd;

	private int length;

	public int length() {
		return length;
	}

	public RakNetEncapsulatedData() {
	}

	public RakNetEncapsulatedData(EncapsulatedPacket msg) {
		append(msg);
	}

	public void append(EncapsulatedPacket msg) {
		packets.add(msg);
		length += msg.getDataSize();
	}

	public boolean isRtoTimeout(long now) {
		return sendTime + rto < now;
	}

	public int getRtt(long now) {
		return (int) (now - sendTime);
	}

	public boolean isAck() {
		return ack;
	}

	public int receive() {
		ack = true;
		return snd();
	}

	public int snd() {
		return snd;
	}

	public int skip() {
		skip++;
		return skip;
	}

	public void set(int id, long now, int rxRto, boolean updateRto) {
		this.id = id;
		skip = 0;
		if (++snd == 1) {
			rto = rxRto;
		} else if (updateRto) {
			rto = Math.min(rto * 2, Constants.RTO_MAX);
		}
		sendTime = now;
	}

	public int getRto() {
		return rto;
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

	public ArrayList<EncapsulatedPacket> getPackets() {
		return packets;
	}

}
