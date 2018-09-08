package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.ScheduledFuture;
import raknetserver.management.ManagementGroup;
import raknetserver.packet.EncapsulatedPacket;
import raknetserver.packet.RakNetConstants;
import raknetserver.packet.raknet.RakNetEncapsulatedData;
import raknetserver.packet.raknet.RakNetPacket;
import raknetserver.packet.raknet.RakNetReliability.REntry;
import raknetserver.packet.raknet.RakNetReliability.RakNetACK;
import raknetserver.packet.raknet.RakNetReliability.RakNetNACK;
import raknetserver.utils.Constants;
import raknetserver.utils.PacketHandlerRegistry;
import raknetserver.utils.RingBuf;
import raknetserver.utils.UInt;
import raknetserver.utils.Utils;

import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

public class RakNetPacketReliabilityHandler extends ChannelDuplexHandler {

    private static final PacketHandlerRegistry<RakNetPacketReliabilityHandler, RakNetPacket> REGISTRY = new PacketHandlerRegistry<>();

    static {
        REGISTRY.register(RakNetEncapsulatedData.class, (ctx, handler, packet) -> handler.handleRakNetEncapsulatedData(ctx, packet));
        REGISTRY.register(RakNetACK.class, (ctx, handler, packet) -> handler.handleRakNetACK(ctx, packet));
        REGISTRY.register(RakNetNACK.class, (ctx, handler, packet) -> handler.handleRakNetNACK(ctx, packet));
    }

    private final LinkedList<EncapsulatedPacket> sendQue = new LinkedList<>();
    private final RingBuf<RakNetEncapsulatedData> sndWindow = new RingBuf<>(8);// capacity greater than snd_window handle resent packets
    private int receiveId;

    private int rxRto = Constants.RTO_DEF;
    private int sRtt;
    private int rxRtt;
    private ScheduledFuture<?> scheduled;
    // ===== Flush reduce
    private boolean flushInProgress;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduled = ctx.channel().eventLoop().scheduleAtFixedRate(() -> updateWindow(ctx), Constants.RTO_INTERVAL, Constants.RTO_INTERVAL, TimeUnit.MILLISECONDS);
        super.channelActive(ctx);
    }

    private void updateWindow(ChannelHandlerContext ctx) {
        if (sndWindow.isEmpty()) {
            return;
        }
        long current = System.currentTimeMillis();
        RingBuf.Range<RakNetEncapsulatedData> range = sndWindow.iterator();
        for (RakNetEncapsulatedData ele : range) {
            if (sndWindow.isFull()) {
                break;
            }
            if (ele.isAck() || ele.getId() != range.id()) {
                continue;
            }
            ele.update(this, current);
            if (ele.isNeedFlush()) {
                sendRakPacket(ctx, ele, current);
            }
        }
        recycle();
        if (!flushInProgress) flushQueue(ctx, current);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        scheduled.cancel(false);
        sendQue.clear();
        sndWindow.resetFully();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RakNetPacket) {
            REGISTRY.handle(ctx, this, (RakNetPacket) msg);
            return;
        }
        ctx.fireChannelRead(msg);
    }

    public void handleRakNetNACK(ChannelHandlerContext ctx, RakNetNACK packet) {
//        for (REntry entry : packet.getEntries()) {
//            handleNAckEntry(ctx, entry);
//        }
    }

    private void handleNAckEntry(ChannelHandlerContext ctx, REntry entry) {
        if (!sndWindow.contains(entry.idfinish)) {
            return;
        }
        int bound = UInt.U24.mod(entry.idfinish, 1);
        int i = sndWindow.contains(entry.idstart) ? entry.idstart : sndWindow.first();
        RingBuf.Range<RakNetEncapsulatedData> range = sndWindow.iterator(i, bound);
        for (RakNetEncapsulatedData ele : range) {
            if (ele.isAck() || range.id() != ele.getId()) {// already changed
                continue;
            }
            ele.setNeedFlush();
        }
    }

    public void handleRakNetACK(ChannelHandlerContext ctx, RakNetACK packet) {
        long current = System.currentTimeMillis();
        for (REntry ackEntry : packet.getEntries()) {
            if (sndWindow.contains(ackEntry.idfinish)) {
                handleAckEntry(ackEntry, current);
            }
        }
        recycle();
        flushQueue(ctx, current);
    }

    private void handleAckEntry(REntry ackEntry, long now) {
        int bound = UInt.U24.mod(ackEntry.idfinish, 1);
        RingBuf.Range<RakNetEncapsulatedData> range = sndWindow.iterator(sndWindow.first(), bound);
        for (RakNetEncapsulatedData ele : range) {
            if (ele.isAck()) {
                continue;
            }
            if (UInt.U24.between(ackEntry.idstart, bound, range.id())) {
                ele.setAck();
                if (range.id() == ele.getId()) {
                    updateRxRto(ele.getRtt(now));
                } else {
                    ManagementGroup.getRakNetPacketReliability().unneededReFlush++;
                }
                continue;
            }
            ele.setFastAck();
        }
    }

    private void recycle() {
        do {
            RakNetEncapsulatedData head = sndWindow.head();
            if (head == null) {
                return;
            }
            if (head.getId() == sndWindow.first() && !head.isAck()) {
                return;
            }
            sndWindow._shift();
        } while (true);
    }

    private void flushQueue(ChannelHandlerContext ctx) {
        flushQueue(ctx, System.currentTimeMillis());
    }

    private int msgAppendLen = -1;

    private void flushQueue(ChannelHandlerContext ctx, long current) {
        if (msgAppendLen == -1) {
            msgAppendLen = ctx.channel().attr(RakNetConstants.MTU).get() - 200;
        }
        do {
            if (sendQue.isEmpty()) {
                onEndFlush(ctx);
                return;
            }
            if (sndWindow.length() >= Constants.SND_WINDOW) {
                onEndFlush(ctx);
                return;
            }
            sendRakPacket(ctx, msgToDataPacket(msgAppendLen), current);
        } while (!inactive);// while not inactive
    }

    private RakNetEncapsulatedData msgToDataPacket(int limit) {
        RakNetEncapsulatedData data = new RakNetEncapsulatedData(sendQue.remove());
        if (!sendQue.isEmpty()) {
            do {
                if (data.length() >= limit) {
                    break;
                }
                EncapsulatedPacket msgToAppend = sendQue.element();
                if (msgToAppend.getDataSize() + data.length() >= limit) {
                    break;
                }
                data.append(sendQue.remove());
            } while (!sendQue.isEmpty());
        }
        return data;
    }

    private void onEndFlush(ChannelHandlerContext ctx) {
        if (flushInProgress) {
            flushInProgress = false;
            ctx.flush();
        }
    }

    private void updateRxRto(int rtt) {
        if (sRtt == 0) {
            sRtt = rtt;
            rxRtt = rtt / 2;
        } else {
            int delta = rtt - sRtt;
            if (delta < 0) delta = -delta;
            rxRtt = (3 * rxRtt + delta) / 4;
            sRtt = (7 * sRtt + rtt) / 8;
            if (sRtt < 1) sRtt = 1;
        }
        rxRto = Utils.bound(Constants.RTO_MIN, sRtt + Math.max(Constants.RTO_INTERVAL, 4 * rxRtt), Constants.RTO_MAX);
    }

    private boolean inactive;

    protected void sendRakPacket(ChannelHandlerContext ctx, RakNetEncapsulatedData data, long current) {
        if (inactive) {
            return;
        }
        data.setFlush(this, sndWindow.add(data), current);
        int xmit = data.getXmit();
        if (xmit == 1) {
            ManagementGroup.getRakNetPacketReliability().msgToPacket++;
        } else {
            if (xmit >= Constants.MAX_RETRANSMISSION) {
                ctx.fireExceptionCaught(ReadTimeoutException.INSTANCE);
                ctx.close();
                inactive = true;
                return;
            }
            ManagementGroup.getRakNetPacketReliability().packetReFlushed++;
        }
        ManagementGroup.getRakNetPacketReliability().packetFlushed++;
        ctx.write(data, ctx.voidPromise());
        if (!flushInProgress) {
            ctx.flush();
        }
    }

    public void handleRakNetEncapsulatedData(ChannelHandlerContext ctx, RakNetEncapsulatedData packet) {
        //check for missing packets
        int id = packet.getId();
        int delta = UInt.U24.delta(receiveId, id);
        //ignore duplicate packet
        if (delta < 1 || delta > UInt.U16.MAX_VALUE) {
            return;
        }
        //send nack for missed packets
        if (delta > 1) {
            ctx.writeAndFlush(new RakNetNACK(UInt.U24.mod(receiveId, 1), UInt.U24.mod(id, -1))).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        }

        receiveId = id;
        //if id is after last received, which means that we don't have any missing packets, send ACK for it

        ctx.writeAndFlush(new RakNetACK(id)).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
        //add encapsulated packet
        for (EncapsulatedPacket encapsulated : packet.getPackets()) {
            ctx.fireChannelRead(encapsulated);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof EncapsulatedPacket) {
            sendQue.add((EncapsulatedPacket) msg);
            ManagementGroup.getRakNetPacketReliability().msgQueued++;
            if (!flushInProgress && sndWindow.length() < Constants.SND_WINDOW) {
                /*
                 * We delay flush operation 1/10 round trip time
                 */
                ctx.channel().eventLoop().schedule(() -> flushQueue(ctx), (sRtt / 10), TimeUnit.MILLISECONDS);
                flushInProgress = true;
            }
        } else {
            ctx.writeAndFlush(msg, promise);
        }
    }

    public int getRxRto() {
        return rxRto;
    }

}
