package raknetserver.management;

import lombok.AllArgsConstructor;
import lombok.Data;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

public class RakNetPacketReliability implements RakNetPacketReliabilityMXBean {

    public int msgQueued;
    public int msgToPacket;
    public int packetFlushed;
    public int packetReFlushed;
    public int fastReFlushed;
    public int rtoReFlushed;
    public int nackReFlush;
    public int unneededReFlush;

    @Override
    public long getMsgQueued() {
        return msgQueued;
    }

    @Override
    public long getPacketFlushed() {
        return packetFlushed;
    }

    @Override
    public long getMsgToPacket() {
        return msgToPacket;
    }

    @Override
    public ReFlush getReFlush() {
        return new ReFlush(packetReFlushed, fastReFlushed, rtoReFlushed, nackReFlush, unneededReFlush);
    }

    @Override
    public void reset() {
        unneededReFlush = nackReFlush = rtoReFlushed = fastReFlushed = msgToPacket = msgQueued = packetFlushed = packetReFlushed = 0;
    }

    @Override
    public ObjectName getObjectName() {
        try {
            return ObjectName.getInstance("raknetserver.management:type=RakNetPacketReliability");
        } catch (MalformedObjectNameException e) {
        }
        return null;
    }

    @Data
    @AllArgsConstructor
    public static class ReFlush {

        public int totalReFlush;
        public int fastReFlush;
        public int transTimeout;
        public int noAck;
        public int unneededReFlush;
    }

}
