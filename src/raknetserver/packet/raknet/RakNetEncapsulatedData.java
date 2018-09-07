package raknetserver.packet.raknet;

import io.netty.buffer.ByteBuf;
import raknetserver.management.ManagementGroup;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.pipeline.raknet.RakNetPacketReliabilityHandler;
import raknetserver.utils.Constants;

import java.util.ArrayList;

public class RakNetEncapsulatedData implements RakNetPacket {

	private final ArrayList<EncapsulatedPacket> packets = new ArrayList<>();
	private int id;
    private int fastAck;
    private boolean ack;

    private int rto;
    private long flushTime;
    private int xmit;

    private long nextFlushTime;
    private boolean needFlush;

	private int length;

	public RakNetEncapsulatedData() {
	}

	public RakNetEncapsulatedData(EncapsulatedPacket msg) {
		append(msg);
	}

	public int length() {
		return length;
	}

	public void append(EncapsulatedPacket msg) {
		packets.add(msg);
		length += msg.getDataSize();
	}

	public boolean isNeedFlush() {
		return needFlush;
	}

	public void setNeedFlush() {
		needFlush = true;
	}

	public boolean isAck() {
		return ack;
	}

	public void setAck() {
		ack = true;
	}

	public int getRtt(long now) {
		return (int) (now - flushTime);
	}

    public int getXmit() {
        return xmit;
    }

    public void setFastAck() {
		fastAck++;
	}

	public void setFlush(RakNetPacketReliabilityHandler stream, int id, long current) {
		this.id = id;
		xmit++;
		if (xmit == 1) {
			rto = stream.getRxRto() + Constants.RTO_INIT_APPEND;
		}
		nextFlushTime = current + rto;
		fastAck = 0;
		needFlush = false;
		flushTime = current;
	}

	public void update(RakNetPacketReliabilityHandler stream, long current) {
		if (needFlush) {// nack set need flush
			ManagementGroup.getRakNetPacketReliability().nackReFlush++;
			return;
		}
		if (current - nextFlushTime >= 0) {// retransmission timeout
			needFlush = true;
			rto += stream.getRxRto();// liner increase
			ManagementGroup.getRakNetPacketReliability().rtoReFlushed++;
			return;
		}
		if (fastAck >= Constants.ACK_FAST_RESENT) {
			needFlush = true;
			ManagementGroup.getRakNetPacketReliability().fastReFlushed++;
		}
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
