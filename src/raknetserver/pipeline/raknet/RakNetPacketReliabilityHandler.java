package raknetserver.pipeline.raknet;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.concurrent.ScheduledFuture;
import raknetserver.packet.EncapsulatedPacket;
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

    private final LinkedList<RakNetEncapsulatedData> sendQue = new LinkedList<>();
    private final RingBuf<RakNetEncapsulatedData> sndWindow = new RingBuf<>(8);// capacity greater than snd_window handle resent packets
    private int receiveId;

    private int rxRto = Constants.RTO_DEF;
    private int sRtt;
    private int rxRtt;
    private ScheduledFuture<?> scheduled;


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        scheduled = ctx.channel().eventLoop().scheduleAtFixedRate(() -> processWindowRto(ctx), Constants.RTO_INTERVAL, Constants.RTO_INTERVAL, TimeUnit.MILLISECONDS);
        super.channelActive(ctx);
    }

    private void processWindowRto(ChannelHandlerContext ctx) {
        if (sndWindow.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        RingBuf.Range<RakNetEncapsulatedData> range = sndWindow.iterator();
        for (RakNetEncapsulatedData ele : range) {
            if (sndWindow.isFull()) {
                break;
            }
            if (ele.isAck() || ele.getId() != range.id()) {
                continue;
            }
            if (ele.isRtoTimeout(now)) {
                // Utils.debug(String.format("!!! rto_rst=%s,rto=%s,snd=%s", range.id(), ele.getRto(), ele.snd()));
                sendRakPacket(ctx, ele, now, true);
            }
        }
        recycle();
        flushQueue(ctx, now);
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
        long now = System.currentTimeMillis();
        for (REntry entry : packet.getEntries()) {
            // Utils.debug("!!! nack_start=" + entry.idstart + ", end=" + entry.idfinish);
            handleNAckEntry(ctx, entry, now);
        }
        recycle();
        flushQueue(ctx, now);
    }

    private void handleNAckEntry(ChannelHandlerContext ctx, REntry entry, long now) {
        if (!sndWindow.contains(entry.idfinish)) {
            // Utils.debug("!!! outdated nack msg");
            return;
        }
        // Utils.debug("!!! nack_start=" + entry.idstart + ", end=" + entry.idfinish);
        int bound = UInt.U24.mod(entry.idfinish, 1);
        int i = sndWindow.contains(entry.idstart) ? entry.idstart : sndWindow.first();
        RingBuf.Range<RakNetEncapsulatedData> range = sndWindow.iterator(i, bound);
        for (RakNetEncapsulatedData ele : range) {
            if (sndWindow.isFull()) {
                break;
            }
            if (ele.isAck() || range.id() != ele.getId()) {// already changed
                continue;
            }
            sendRakPacket(ctx, ele, now, false);// not update rto in nack resend
        }
    }

    public void handleRakNetACK(ChannelHandlerContext ctx, RakNetACK packet) {
        long now = System.currentTimeMillis();
        for (REntry entry : packet.getEntries()) {
            handleAckEntry(ctx, entry, now);
        }
        recycle();
        flushQueue(ctx, now);
    }

    private void handleAckEntry(ChannelHandlerContext ctx, REntry entry, long now) {
        if (!sndWindow.contains(entry.idfinish)) {
            // Utils.debug("!!! outdated ack msg");
            return;
        }
        // Utils.debug("!!! ack_start=" + entry.idstart + ", end=" + entry.idfinish);
        int bound = UInt.U24.mod(entry.idfinish, 1);
        RingBuf.Range<RakNetEncapsulatedData> range = sndWindow.iterator(sndWindow.first(), bound);
        for (RakNetEncapsulatedData ele : range) {
            if (ele.isAck() || range.id() != ele.getId()) {// already changed
                continue;
            }
            if (UInt.U24.between(entry.idstart, bound, range.id())) {
                int snd = ele.receive();
                if (snd == 1) {
                    updateRxRto(ele.getRtt(now));
                }
                continue;
            }
            int i = ele.skip();
            if (i > Constants.ACK_FAST_RESENT && !sndWindow.isFull()) {
                // Utils.debug("!!! fast_rst=" + range.id());
                sendRakPacket(ctx, ele, now, false);
            }
        }
    }

    private void recycle() {
        RakNetEncapsulatedData head = sndWindow.head();
        if (head == null) {
            // Utils.debug("!!! recycle_snd_window=" + sndWindow.length());
            return;
        }
        if (head.getId() == sndWindow.first() && !head.isAck()) {
            // Utils.debug("!!! recycle_snd_window=" + sndWindow.length());
            return;
        }
        sndWindow._shift();
        recycle();
    }

    private void flushQueue(ChannelHandlerContext ctx, long now) {
        if (sendQue.isEmpty()) {
            return;
        }
        if (sndWindow.length() >= Constants.SND_WINDOW) {
            return;
        }
        sendRakPacket(ctx, sendQue.remove(), now, true);
        flushQueue(ctx, now);
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

    protected void sendRakPacket(ChannelHandlerContext ctx, RakNetEncapsulatedData data, long now, boolean updateRto) {
        if (data.getRto() == Constants.RTO_MAX) {
            System.out.println(String.format("!!! snd=%s", data.snd()));
            throw ReadTimeoutException.INSTANCE;
        }
        int id = sndWindow.add(data);
        data.set(id, now, rxRto, updateRto);
        ctx.writeAndFlush(data).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
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
            // Utils.debug("!!! snd_nack_start=" + UInt.U24.mod(receiveId, 1) + ", end=" + UInt.U24.mod(id, -1));
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
            RakNetEncapsulatedData data = new RakNetEncapsulatedData((EncapsulatedPacket) msg);
            if (sndWindow.length() < Constants.SND_WINDOW) {
                sendRakPacket(ctx, data, System.currentTimeMillis(), true);
            } else {
                sendQue.add(data);
            }
        } else {
            ctx.write(msg, promise);
        }
    }

}
