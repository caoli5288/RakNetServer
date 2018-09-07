package raknetserver.management;

import java.lang.management.PlatformManagedObject;

public interface RakNetPacketReliabilityMXBean extends PlatformManagedObject {

    long getMsgQueued();

    long getMsgToPacket();

    long getPacketFlushed();

    RakNetPacketReliability.ReFlush getReFlush();

    void reset();
}
