package raknetserver.management;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.NotCompliantMBeanException;
import java.lang.management.ManagementFactory;

public class ManagementGroup {

    private static final RakNetPacketReliability RAK_NET_PACKET_RELIABILITY = new RakNetPacketReliability();

    static {
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        try {
            server.registerMBean(RAK_NET_PACKET_RELIABILITY, RAK_NET_PACKET_RELIABILITY.getObjectName());
        } catch (InstanceAlreadyExistsException | MBeanRegistrationException | NotCompliantMBeanException e) {
        }
    }

    public static RakNetPacketReliability getRakNetPacketReliability() {
        return RAK_NET_PACKET_RELIABILITY;
    }

}
