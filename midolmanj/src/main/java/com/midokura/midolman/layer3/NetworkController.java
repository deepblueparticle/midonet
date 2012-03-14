/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.management.JMException;

import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFFlowRemoved;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionDataLayer;
import org.openflow.protocol.action.OFActionDataLayerDestination;
import org.openflow.protocol.action.OFActionDataLayerSource;
import org.openflow.protocol.action.OFActionNetworkLayerAddress;
import org.openflow.protocol.action.OFActionNetworkLayerDestination;
import org.openflow.protocol.action.OFActionNetworkLayerSource;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionTransportLayer;
import org.openflow.protocol.action.OFActionTransportLayerDestination;
import org.openflow.protocol.action.OFActionTransportLayerSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.actors.threadpool.Arrays;

import com.midokura.midolman.AbstractController;
import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.Router.Action;
import com.midokura.midolman.layer3.Router.ForwardInfo;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.ARP;
import com.midokura.midolman.packets.DHCP;
import com.midokura.midolman.packets.DHCPOption;
import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.ICMP;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.IPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.packets.MalformedPacketException;
import com.midokura.midolman.packets.TCP;
import com.midokura.midolman.packets.UDP;
import com.midokura.midolman.portservice.PortService;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.IPv4Set;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortSetMap;
import com.midokura.midolman.state.PortToIntNwAddrMap;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.Callback;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.ShortUUID;

public class NetworkController extends AbstractController
    implements ServiceFlowController {

    private static final Logger log = LoggerFactory
            .getLogger(NetworkController.class);

    // TODO(pino): This constant should be declared in openflow...
    public static final short NO_HARD_TIMEOUT = 0;
    public static final short NO_IDLE_TIMEOUT = 0;
    // TODO(pino)
    public static final short ICMP_EXPIRY_SECONDS = 5;
    private static final short FLOW_PRIORITY = 0;
    private static final short SERVICE_FLOW_PRIORITY = FLOW_PRIORITY + 1;
    public static final int ICMP_TUNNEL = 0x05;

    private PortZkManager portMgr;
    private RouteZkManager routeMgr;
    Network network;
    private Map<UUID, L3DevicePort> devPortById;
    private Map<Integer, L3DevicePort> devPortByNum;
    short idleFlowExpireSeconds; //package private to allow test access.

    private PortService service;
    private Map<UUID, List<Runnable>> portServicesById;
    // Store port num of a port that has a service port.
    private short serviceTargetPort;
    // Track which routers processed an installed flow.
    private Map<MidoMatch, Set<UUID>> matchToRouters;
    // The ports which make up the multiports.
    // TODO: Should this be part of PortZkManager?
    private PortSetMap portSetMap;

    public NetworkController(long datapathId, UUID deviceId, int greKey,
            PortToIntNwAddrMap dict, short idleFlowExpireSeconds,
            IntIPv4 localNwAddr, PortZkManager portMgr,
            RouterZkManager routerMgr, RouteZkManager routeMgr,
            ChainZkManager chainMgr, RuleZkManager ruleMgr,
            OpenvSwitchDatabaseConnection ovsdb, Reactor reactor, Cache cache,
            String externalIdKey, PortService service, PortSetMap portSetMap) {
        super(datapathId, deviceId, greKey, ovsdb, dict, localNwAddr, 
              externalIdKey);
        this.idleFlowExpireSeconds = idleFlowExpireSeconds;
        this.portMgr = portMgr;
        this.routeMgr = routeMgr;
        this.network = new Network(deviceId, portMgr, routerMgr, chainMgr,
                ruleMgr, reactor, cache);
        this.devPortById = new HashMap<UUID, L3DevicePort>();
        this.devPortByNum = new HashMap<Integer, L3DevicePort>();
        this.portSetMap = portSetMap;

        this.service = service;
        this.service.setController(this);
        this.portServicesById = new HashMap<UUID, List<Runnable>>();
        this.matchToRouters = new HashMap<MidoMatch, Set<UUID>>();
    }
    
    /*
     * Setup a flow that sends all DHCP request packets to 
     * the controller.
     */
    private void setFlowsForHandlingDhcpInController(L3DevicePort devPortIn) {
        log.debug("setFlowsForHandlingDhcpInController: on port {}", devPortIn);
        
        MidoMatch match = new MidoMatch();
        match.setInputPort(devPortIn.getNum());
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(UDP.PROTOCOL_NUMBER);
        match.setTransportSource((short) 68);
        match.setTransportDestination((short) 67);
        
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 1024));

        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);
    }
    
    private void handleDhcpRequest(L3DevicePort devPortIn, DHCP request,
            MAC sourceMac) {
        byte[] chaddr = request.getClientHardwareAddress();
        if (null == chaddr) {
            log.warn("handleDhcpRequest dropping bootrequest with null chaddr");
            return;
        }
        if (chaddr.length != 6) {
            log.warn("handleDhcpRequest dropping bootrequest with chaddr "
                    + "with length {} greater than 6.", chaddr.length);
            return;
        }
        log.debug("handleDhcpRequest: on port {} bootrequest with chaddr {} "
                + "and ciaddr {}",
                new Object[] { devPortIn, Net.convertByteMacToString(chaddr),
                        IPv4.fromIPv4Address(request.getClientIPAddress()) });

        // Extract all the options and put them in a map
        Map<Byte, DHCPOption> reqOptions = new HashMap<Byte, DHCPOption>();
        Set<Byte> requestedCodes = new HashSet<Byte>();
        for (DHCPOption opt : request.getOptions()) {
            byte code = opt.getCode();
            reqOptions.put(code, opt);
            log.debug("handleDhcpRequest found option {}:{}", code,
                    DHCPOption.codeToName.get(code));
            if (code == DHCPOption.Code.DHCP_TYPE.value()) {
                if (opt.getLength() != 1) {
                    log.warn("handleDhcpRequest dropping bootrequest - "
                            + "dhcp msg type option has bad length or data.");
                    return;
                }
                log.debug("handleDhcpRequest dhcp msg type {}:{}",
                        opt.getData()[0],
                        DHCPOption.msgTypeToName.get(opt.getData()[0]));
            }
            if (code == DHCPOption.Code.PRM_REQ_LIST.value()) {
                if (opt.getLength() <= 0) {
                    log.warn("handleDhcpRequest dropping bootrequest - "
                            + "param request list has bad length");
                    return;
                }
                for (int i = 0; i < opt.getLength(); i++) {
                    byte c = opt.getData()[i];
                    requestedCodes.add(c);
                    log.debug("handleDhcpRequest client requested option "
                            + "{}:{}", c, DHCPOption.codeToName.get(c));
                }
            }
        }
        DHCPOption typeOpt = reqOptions.get(DHCPOption.Code.DHCP_TYPE.value());
        if (null == typeOpt) {
            log.warn("handleDhcpRequest dropping bootrequest - no dhcp msg "
                    + "type found.");
            return;
        }
        byte msgType = typeOpt.getData()[0];
        boolean drop = true;
        List<DHCPOption> options = new ArrayList<DHCPOption>();
        DHCPOption opt;
        if (DHCPOption.MsgType.DISCOVER.value() == msgType) {
            drop = false;
            // Reply with a dchp OFFER.
            opt = new DHCPOption(DHCPOption.Code.DHCP_TYPE.value(),
                    DHCPOption.Code.DHCP_TYPE.length(),
                    new byte[] { DHCPOption.MsgType.OFFER.value() });
            options.add(opt);

        } else if (DHCPOption.MsgType.REQUEST.value() == msgType) {
            drop = false;
            // Reply with a dchp ACK.
            opt = new DHCPOption(DHCPOption.Code.DHCP_TYPE.value(),
                    DHCPOption.Code.DHCP_TYPE.length(),
                    new byte[] { DHCPOption.MsgType.ACK.value() });
            options.add(opt);
            // http://tools.ietf.org/html/rfc2131 Section 3.1, Step 3:
            // "The client broadcasts a DHCPREQUEST message that MUST include
            // the 'server identifier' option to indicate which server is has
            // selected."
            // TODO(pino): figure out why Linux doesn't send us the server id
            // and try re-enabling this code.
            opt = reqOptions.get(DHCPOption.Code.SERVER_ID
                    .value());
            if (null == opt) {
                log.warn("handleDhcpRequest dropping dhcp REQUEST - no " +
                		"server id option found.");
                // TODO(pino): re-enable this.
                //return;
            } else {
                // The server id should correspond to this port's address.
                int ourServId = devPortIn.getVirtualConfig().portAddr;
                int theirServId = IPv4.toIPv4Address(opt.getData());
                if (ourServId != theirServId) {
                    log.warn("handleDhcpRequest dropping dhcp REQUEST - client "
                            + "chose server {} not us {}",
                            IPv4.fromIPv4Address(theirServId),
                            IPv4.fromIPv4Address(ourServId));
                }
            }
            // The request must contain a requested IP address option.
            opt = reqOptions.get(DHCPOption.Code.REQUESTED_IP.value());
            if (null == opt) {
                log.warn("handleDhcpRequest dropping dhcp REQUEST - no "
                        + "requested ip option found.");
                return;
            }
            // The requested ip must correspond to the yiaddr in our offer.
            int reqIp = IPv4.toIPv4Address(opt.getData());
            int offeredIp = devPortIn.getVirtualConfig().localNwAddr;
            // TODO(pino): must keep state and remember the offered ip based
            // on the chaddr or the client id option.
            if (reqIp != offeredIp) {
                log.warn("handleDhcpRequest dropping dhcp REQUEST - the " +
                        "requested ip {} is not the offered yiaddr {}",
                        IPv4.fromIPv4Address(reqIp), IPv4.fromIPv4Address(offeredIp));
                // TODO(pino): send a dhcp NAK reply.
                return;
            }
        }
        if (drop) {
            log.warn("handleDhcpRequest dropping bootrequest - we don't "
                    + "handle msg type {}:{}", msgType,
                    DHCPOption.msgTypeToName.get(msgType));
            return;
        }
        DHCP reply = new DHCP();
        reply.setOpCode(DHCP.OPCODE_REPLY);
        reply.setTransactionId(request.getTransactionId());
        reply.setHardwareAddressLength((byte) 6);
        reply.setHardwareType((byte) ARP.HW_TYPE_ETHERNET);
        reply.setClientHardwareAddress(sourceMac);
        reply.setServerIPAddress(devPortIn.getVirtualConfig().portAddr);
        // TODO(pino): use explicitly assigned address not localNwAddr!!
        reply.setYourIPAddress(devPortIn.getVirtualConfig().localNwAddr);
        // TODO(pino): do we need to include the DNS option?
        opt = new DHCPOption(DHCPOption.Code.MASK.value(),
                DHCPOption.Code.MASK.length(),
                IPv4.toIPv4AddressBytes(~0 << (32 - devPortIn
                        .getVirtualConfig().nwLength)));
        options.add(opt);
        // Generate the broadcast address... this is nwAddr with 1's in the 
        // last 32-nwAddrLength bits.
        int mask = ~0 >>> devPortIn.getVirtualConfig().nwLength;
        int bcast = mask | devPortIn.getVirtualConfig().nwAddr;
        log.debug("handleDhcpRequest setting bcast addr option to {}",
                IPv4.fromIPv4Address(bcast));
        opt = new DHCPOption(
                DHCPOption.Code.BCAST_ADDR.value(),
                DHCPOption.Code.BCAST_ADDR.length(),
                IPv4.toIPv4AddressBytes(bcast));
        options.add(opt);
        opt = new DHCPOption(
                DHCPOption.Code.IP_LEASE_TIME.value(),
                DHCPOption.Code.IP_LEASE_TIME.length(),
                // This is in seconds... is 1 day enough?
                IPv4.toIPv4AddressBytes(86400));
        options.add(opt);
        opt = new DHCPOption(
                DHCPOption.Code.ROUTER.value(),
                DHCPOption.Code.ROUTER.length(),
                IPv4.toIPv4AddressBytes(devPortIn.getVirtualConfig().portAddr));
        options.add(opt);
        // in MidoNet the DHCP server is the same as the router
        opt = new DHCPOption(
                DHCPOption.Code.SERVER_ID.value(),
                DHCPOption.Code.SERVER_ID.length(),
                IPv4.toIPv4AddressBytes(devPortIn.getVirtualConfig().portAddr));
        options.add(opt);
        // And finally add the END option.
        opt = new DHCPOption(DHCPOption.Code.END.value(),
                DHCPOption.Code.END.length(), null);
        options.add(opt);
        reply.setOptions(options);

        UDP udp = new UDP();
        udp.setSourcePort((short) 67);
        udp.setDestinationPort((short) 68);
        udp.setPayload(reply);
        
        IPv4 ip = new IPv4();
        ip.setSourceAddress(devPortIn.getVirtualConfig().portAddr);
        ip.setDestinationAddress("255.255.255.255");
        ip.setProtocol(UDP.PROTOCOL_NUMBER);
        ip.setPayload(udp);
        
        Ethernet eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);

        eth.setSourceMACAddress(devPortIn.getMacAddr());
        eth.setDestinationMACAddress(sourceMac);
        
        log.debug("handleDhcpRequest: sending DHCP reply {} to port {}", eth,
                devPortIn);
        sendUnbufferedPacketFromPort(eth, devPortIn.getNum());
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short shortInPort,
            byte[] data, long matchingTunnelId) {
        int inPort = shortInPort & 0xffff;
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, shortInPort);
        L3DevicePort devPortOut;

        // Rewrite inPort with the service's target port assuming that
        // service flows sent this packet to the OFPP_CONTROLLER.
        // TODO(yoshi): replace this with better mechanism such as ARP proxy
        // for service ports.
        if (inPort == (OFPort.OFPP_LOCAL.getValue() & 0xffff)) {
            log.debug("onPacketIn: rewrite port {} to {}", inPort,
                      serviceTargetPort);
            inPort = serviceTargetPort;
        }

        // Try mapping the port number to a virtual device port.
        L3DevicePort devPortIn = devPortByNum.get(inPort);
        // If the port isn't a virtual port and it isn't a tunnel, drop the pkt.
        if (null == devPortIn && !super.isTunnelPortNum(inPort)) {
            log.warn("onPacketIn: dropping packet from port {} (not virtual "
                    + "or tunnel): {}", inPort, match);
            // TODO(pino): should we install a drop rule to avoid processing
            // all the packets from this port?
            freeBuffer(bufferId);
            return;
        }

        ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
        Ethernet ethPkt = new Ethernet();
        try {
            ethPkt.deserialize(bb);
        } catch(MalformedPacketException ex) {
            // Packet could not be deserialized: Drop it.
            log.warn("onPacketIn: dropping malformed packet from port {}.  "
                    + "{}", inPort, ex.getMessage());
            freeBuffer(bufferId);
            return;
        }

        log.debug("onPacketIn: port {} received buffer {} of size {} - {}",
                new Object [] { inPort, bufferId, totalLen, ethPkt });

        if (super.isTunnelPortNum(inPort)) {
            log.debug("onPacketIn: got packet from tunnel {}", inPort);

            // TODO: Check for multicast packets we generated ourself for a
            // group we're in, and drop them.

            // TODO: Check for the broadcast address, and if so use the
            // broadcast
            // ethernet address for the dst MAC.
            // We can check the broadcast address by looking up the gateway in
            // Zookeeper to get the prefix length of its network.

            // TODO: Do address spoofing prevention: if the source
            // address doesn't match the vport's, drop the flow.

            // Extract the gateway IP and vport uuid.
            DecodedMacAddrs portsAndGw = decodeMacAddrs(match
                    .getDataLayerSource(), match.getDataLayerDestination());

            log.debug("onPacketIn: from tunnel port {} decoded mac {}",
                    inPort, portsAndGw);
            
            // If we don't own the egress port, there was a forwarding mistake.
            devPortOut = devPortById.get(portsAndGw.lastEgressPortId);
            
            if (null == devPortOut) {
                log.warn("onPacketIn: the egress port {} is not local", portsAndGw.lastEgressPortId);
                // TODO: raise an exception or install a Blackhole?
                return;
            }
            
            TunneledPktArpCallback cb = new TunneledPktArpCallback(bufferId,
                    totalLen, inPort, data, match, portsAndGw);
            try {
                log.debug("onPacketIn: need mac for ip {} on port {}",
                        IPv4.fromIPv4Address(portsAndGw.nextHopNwAddr),
                        portsAndGw.lastEgressPortId);
                
                network.getMacForIp(portsAndGw.lastEgressPortId,
                        portsAndGw.nextHopNwAddr, cb);
            } catch (ZkStateSerializationException e) {
                log.warn("onPacketIn", e);
            }
            // The ARP will be completed asynchronously by the callback.
            return;
        }

        // check if the packet is a DHCP request
        if (ethPkt.getEtherType() == IPv4.ETHERTYPE) {
            IPv4 ipv4 = (IPv4) ethPkt.getPayload();
            if (ipv4.getProtocol() == UDP.PROTOCOL_NUMBER) {
                UDP udp = (UDP) ipv4.getPayload();
                if (udp.getSourcePort() == 68 && udp.getDestinationPort() == 67) {
                    DHCP dhcp = (DHCP) udp.getPayload();
                    if (dhcp.getOpCode() == DHCP.OPCODE_REQUEST) {
                        log.debug("onPacketIn: got a DHCP bootrequest");
                        handleDhcpRequest(devPortIn, dhcp,
                                ethPkt.getSourceMACAddress());
                        freeBuffer(bufferId);
                        return;
                    }
                }
            }
        }
        
        // Drop the packet if it's not addressed to an L2 mcast address or
        // the ingress port's own address.
        // TODO(pino): check this with Jacob.
        if (!ethPkt.getDestinationMACAddress().equals(devPortIn.getMacAddr())
                && !ethPkt.isMcast()) {
            log.warn("onPacketIn: dlDst {} not mcast nor virtual port's addr", ethPkt.getDestinationMACAddress());
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT, ICMP_EXPIRY_SECONDS);
            return;
        }
        ForwardInfo fwdInfo = new ForwardInfo();
        fwdInfo.inPortId = devPortIn.getId();
        fwdInfo.flowMatch = match;
        fwdInfo.matchIn = match.clone();
        fwdInfo.pktIn = ethPkt;
        Set<UUID> routers = new HashSet<UUID>();
        try {
            network.process(fwdInfo, routers);
        } catch (Exception e) {
            log.warn("onPacketIn dropping packet: ", e);
            freeBuffer(bufferId);
            freeFlowResources(match, routers);
            return;
        }
        boolean useWildcards = false; // TODO(pino): replace with real config.

        MidoMatch flowMatch;
        switch (fwdInfo.action) {
        case BLACKHOLE:
            // TODO(pino): the following wildcarding seems too aggressive.
            // If wildcards are enabled, wildcard everything but nw_src and
            // nw_dst.
            // This is meant to protect against DOS attacks by preventing ipc's
            // to
            // the Openfaucet controller if mac addresses or tcp ports are
            // cycled.
            if (useWildcards)
                flowMatch = makeWildcarded(match);
            else
                flowMatch = match;
            log.debug("onPacketIn: Network.process() returned BLACKHOLE for {}", fwdInfo);
            installBlackhole(flowMatch, bufferId, NO_IDLE_TIMEOUT,
                    ICMP_EXPIRY_SECONDS);
            notifyFlowAdded(match, flowMatch, devPortIn.getId(), fwdInfo,
                    routers);
            freeFlowResources(match, routers);
            return;
        case CONSUMED:
            log.debug("onPacketIn: Network.process() returned CONSUMED for {}", fwdInfo);
            freeBuffer(bufferId);
            return;
        case FORWARD:
            // If the egress port is local, ARP and forward the packet.
            devPortOut = devPortById.get(fwdInfo.outPortId);
            if (null != devPortOut) {
                log.debug("onPacketIn: Network.process() returned FORWARD to " +
                          "local port {} for {}", devPortOut, fwdInfo);
                LocalPktArpCallback cb = new LocalPktArpCallback(bufferId,
                        totalLen, devPortIn, data, match, fwdInfo, ethPkt,
                        routers);
                try {
                    network.getMacForIp(fwdInfo.outPortId,
                            fwdInfo.nextHopNwAddr, cb);
                } catch (ZkStateSerializationException e) {
                    log.warn("onPacketIn dropping the packet: ", e);
                    freeBuffer(bufferId);
                    freeFlowResources(match, routers);
                }
            } else { // devPortOut is null; the egress port is remote or multiple.
                log.debug("onPacketIn: Network.process() returned FORWARD to "
                        + "remote/multi port {} for {}", fwdInfo.outPortId, fwdInfo);

                if (portSetMap.containsKey(fwdInfo.outPortId)) {
                    Set<Short> outPorts = new HashSet<Short>();
                    // XXX: Add local OVS ports.
                    IPv4Set remoteControllersAddrs = portSetMap.get(fwdInfo.outPortId);
                    if (remoteControllersAddrs == null)
                        log.error("Can't find portset ID {}", fwdInfo.outPortId);
                    else for (String remoteControllerAddr : 
                            remoteControllersAddrs.getStrings()) {
                        Integer portNum = tunnelPortNumOfPeer(
                                              IntIPv4.fromString(remoteControllerAddr));
                        if (portNum != null) {
                            outPorts.add(new Short(portNum.shortValue()));
                        } else {
                            log.warn("onPacketIn:  No OVS tunnel port found " +
                                     "for Controller at {}", remoteControllerAddr);
                        }
                    }

                    if (outPorts.size() == 0) {
                        log.warn("onPacketIn:  No OVS ports or tunnels found " +
                                 "for portset {}", fwdInfo.outPortId);

                        installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                                ICMP_EXPIRY_SECONDS);
                        freeFlowResources(match, routers);
                        // TODO: check whether this is the right error code (host?).
                        sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET,
                                devPortIn.getId(), ethPkt, fwdInfo.inPortId,
                                fwdInfo.pktIn, fwdInfo.outPortId);
                        return;
                    }

                    List<OFAction> ofActions = makeActionsForFlow(match,
                            fwdInfo.matchOut, outPorts);
                    // Track the routers for this flow so we can free resources
                    // when the flow is removed.
                    matchToRouters.put(match, routers);
                    addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                            NO_HARD_TIMEOUT, true, ofActions, inPort, data);
                    return;
                }

                Integer tunPortNum = 
                        super.portUuidToTunnelPortNumber(fwdInfo.outPortId);
                if (null == tunPortNum) {
                    log.warn("onPacketIn:  No tunnel port found for {}",
                            fwdInfo.outPortId);
                    
                    installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                            ICMP_EXPIRY_SECONDS);
                    freeFlowResources(match, routers);
                    // TODO: check whether this is the right error code (host?).
                    sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET,
                            devPortIn.getId(), ethPkt, fwdInfo.inPortId,
                            fwdInfo.pktIn, fwdInfo.outPortId);
                    return;
                }

                log.debug("onPacketIn: FORWARDing to tunnel port {}",
                        tunPortNum);
                MAC[] dlHeaders = getDlHeadersForTunnel(
                        ShortUUID.UUID32toInt(fwdInfo.inPortId),
                        ShortUUID.UUID32toInt(fwdInfo.outPortId),
                        fwdInfo.nextHopNwAddr);
                
                fwdInfo.matchOut.setDataLayerSource(dlHeaders[0]);
                fwdInfo.matchOut.setDataLayerDestination(dlHeaders[1]);
                
                List<OFAction> ofActions = makeActionsForFlow(match,
                        fwdInfo.matchOut, tunPortNum.shortValue());
                // TODO(pino): should we do any wildcarding here?
                // Track the routers for this flow so we can free resources
                // when the flow is removed.
                matchToRouters.put(match, routers);
                addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                        NO_HARD_TIMEOUT, true, ofActions, inPort, data);
            }
            return;
        case NOT_IPV4:
            log.debug("onPacketIn: Network.process() returned NOT_IPV4, " +
                      "ethertype is {}", match.getDataLayerType());
            // Wildcard everything but dl_type. One rule per EtherType.
            short dlType = match.getDataLayerType();
            match = new MidoMatch();
            match.setDataLayerType(dlType);
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT ,NO_HARD_TIMEOUT);
            return;
        case NO_ROUTE:
            log.debug("onPacketIn: Network.process() returned NO_ROUTE for {}",
                    fwdInfo);
            // Intentionally use an exact match for this drop rule.
            // TODO(pino): wildcard the L2 fields.
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT, 
                    ICMP_EXPIRY_SECONDS);
            freeFlowResources(match, routers);
            // Send an ICMP
            sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_NET, devPortIn
                    .getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                    fwdInfo.outPortId);
            // This rule is temporary, don't notify the flow checker.
            return;
        case REJECT:
            log.debug("onPacketIn: Network.process() returned REJECT for {}",
                    fwdInfo);
            // Intentionally use an exact match for this drop rule.
            installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                    ICMP_EXPIRY_SECONDS);
            freeFlowResources(match, routers);
            // Send an ICMP
            sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_FILTER_PROHIB,
                    devPortIn.getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                    fwdInfo.outPortId);
            // This rule is temporary, don't notify the flow checker.
            return;
        default:
            log.error("onPacketIn: Network.process() returned unrecognized action {}",
                    fwdInfo.action);
            throw new RuntimeException("Unrecognized forwarding Action type " + fwdInfo.action);
        }
    }

    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, short outPortNum) {
        Set<Short> portSet = new HashSet<Short>();
        portSet.add(outPortNum);
        return makeActionsForFlow(origMatch, newMatch, portSet);
    }

    private List<OFAction> makeActionsForFlow(MidoMatch origMatch,
            MidoMatch newMatch, Set<Short> outPorts) {
        // Create OF actions for fields that changed from original to last
        // match.
        List<OFAction> actions = new ArrayList<OFAction>();
        OFAction action = null;
        if (!Arrays.equals(origMatch.getDataLayerSource(), newMatch
                .getDataLayerSource())) {
            action = new OFActionDataLayerSource();
            ((OFActionDataLayer) action).setDataLayerAddress(newMatch
                    .getDataLayerSource());
            actions.add(action);
        }
        if (!Arrays.equals(origMatch.getDataLayerDestination(), newMatch
                .getDataLayerDestination())) {
            action = new OFActionDataLayerDestination();
            ((OFActionDataLayer) action).setDataLayerAddress(newMatch
                    .getDataLayerDestination());
            actions.add(action);
        }
        if (origMatch.getNetworkSource() != newMatch.getNetworkSource()) {
            action = new OFActionNetworkLayerSource();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(newMatch
                    .getNetworkSource());
            actions.add(action);
        }
        if (origMatch.getNetworkDestination() != newMatch
                .getNetworkDestination()) {
            action = new OFActionNetworkLayerDestination();
            ((OFActionNetworkLayerAddress) action).setNetworkAddress(newMatch
                    .getNetworkDestination());
            actions.add(action);
        }
        if (origMatch.getTransportSource() != newMatch.getTransportSource()) {
            action = new OFActionTransportLayerSource();
            ((OFActionTransportLayer) action).setTransportPort(newMatch
                    .getTransportSource());
            actions.add(action);
        }
        if (origMatch.getTransportDestination() != newMatch
                .getTransportDestination()) {
            action = new OFActionTransportLayerDestination();
            ((OFActionTransportLayer) action).setTransportPort(newMatch
                    .getTransportDestination());
            actions.add(action);
        }
        for (Short outPortNum : outPorts) {
            action = new OFActionOutput(outPortNum.shortValue(), (short) 0);
            actions.add(action);
        }
        return actions;
    }

    private MidoMatch makeWildcardedFromTunnel(MidoMatch m1) {
        // TODO Auto-generated method stub
        return m1;
    }

    public static MAC[] getDlHeadersForTunnel(
            int lastInPortId, int lastEgPortId, int gwNwAddr) {
        byte[] dlSrc = new byte[6];
        byte[] dlDst = new byte[6];
        
        // Set the data layer source and destination:
        // The ingress port is used as the high 32 bits of the source mac.
        // The egress port is used as the low 32 bits of the dst mac.
        // The high 16 bits of the gwNwAddr are the low 16 bits of the src mac.
        // The low 16 bits of the gwNwAddr are the high 16 bits of the dst mac.
        for (int i = 0; i < 4; i++)
            dlSrc[i] = (byte) (lastInPortId >> (3 - i) * 8);
        dlSrc[4] = (byte) (gwNwAddr >> 24);
        dlSrc[5] = (byte) (gwNwAddr >> 16);
        dlDst[0] = (byte) (gwNwAddr >> 8);
        dlDst[1] = (byte) (gwNwAddr);
        for (int i = 2; i < 6; i++)
            dlDst[i] = (byte) (lastEgPortId >> (5 - i) * 8);
        
        return new MAC[] {new MAC(dlSrc), new MAC(dlDst)};
    }

    public static class DecodedMacAddrs {
        UUID lastIngressPortId;
        UUID lastEgressPortId;
        int nextHopNwAddr;
        
        public String toString() {
            return String.format("DecodedMacAddrs: ingress %s egress %s nextHopIp %s",
                    lastIngressPortId,
                    lastEgressPortId,
                    Net.convertIntAddressToString(nextHopNwAddr));
        }
    }

    public static DecodedMacAddrs decodeMacAddrs(final byte[] src,
            final byte[] dst) {
        DecodedMacAddrs result = new DecodedMacAddrs();
        int port32BitId = 0;
        for (int i = 0; i < 4; i++)
            port32BitId |= (src[i] & 0xff) << ((3 - i) * 8);
        result.lastIngressPortId = ShortUUID.intTo32BitUUID(port32BitId);
        result.nextHopNwAddr = (src[4] & 0xff) << 24;
        result.nextHopNwAddr |= (src[5] & 0xff) << 16;
        result.nextHopNwAddr |= (dst[0] & 0xff) << 8;
        result.nextHopNwAddr |= (dst[1] & 0xff);
        port32BitId = 0;
        for (int i = 2; i < 6; i++)
            port32BitId |= (dst[i] & 0xff) << (5 - i) * 8;
        result.lastEgressPortId = ShortUUID.intTo32BitUUID(port32BitId);
        return result;
    }

    private class TunneledPktArpCallback implements Callback<MAC> {
        public TunneledPktArpCallback(int bufferId, int totalLen, int inPort,
                byte[] data, MidoMatch match, DecodedMacAddrs portsAndGw) {
            super();
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = inPort;
            this.data = data;
            this.match = match;
            this.portsAndGw = portsAndGw;
        }

        int bufferId;
        int totalLen;
        int inPort;
        byte[] data;
        MidoMatch match;
        DecodedMacAddrs portsAndGw;

        @Override
        public void call(MAC mac) {
            String nwDstStr = IPv4.fromIPv4Address(match
                    .getNetworkDestination());
            if (null != mac) {
                log.debug("TunneledPktArpCallback.call: Mac resolved for tunneled packet to {}", nwDstStr);
                L3DevicePort devPort = devPortById
                        .get(portsAndGw.lastEgressPortId);
                
                if (null == devPort) {
                    log.warn("TunneledPktArpCallback.call: port {} is no longer local", portsAndGw.lastEgressPortId);
                    // TODO(pino): do we need to do anything for this?
                    // The port was removed while we waited for the ARP.
                    return;
                }
                
                MidoMatch newMatch = match.clone();
                // TODO(pino): get the port's mac address from the ZK config.
                newMatch.setDataLayerSource(devPort.getMacAddr());
                newMatch.setDataLayerDestination(mac);
                List<OFAction> ofActions = makeActionsForFlow(match, newMatch,
                        devPort.getNum());
                boolean useWildcards = true; // TODO: get this from config.
                if (useWildcards) {
                    // TODO: Should we check for non-load-balanced routes and
                    // wild-card flows matching them on layer 3 and lower?
                    // inPort, dlType, nwSrc.
                    match = makeWildcardedFromTunnel(match);
                }

                // If this is an ICMP error message from a peer controller,
                // don't install a flow match, just send the packet
                if (ShortUUID.UUID32toInt(portsAndGw.lastIngressPortId) == ICMP_TUNNEL) {
                    log.debug("TunneledPktArpCallback.call: forward ICMP without installing flow");
                    NetworkController.super.controllerStub.sendPacketOut(
                            bufferId, (short)inPort, ofActions, data);
                } else {
                    log.debug("TunneledPktArpCallback.call: forward and install flow {}", match);
                    addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                            NO_HARD_TIMEOUT, true, ofActions, (short)inPort, data);
                }
            } else {
                log.debug("TunneledPktArpCallback.call: ARP timed out for tunneled packet to {}, send ICMP",
                        nwDstStr);
                installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                        ICMP_EXPIRY_SECONDS);
                // Send an ICMP !H
                ByteBuffer bb = ByteBuffer.wrap(data, 0, data.length);
                Ethernet ethPkt = new Ethernet();
                try {
                    ethPkt.deserialize(bb);
                } catch (MalformedPacketException ex) {
                    // Packet could not be deserialized: Drop it.
                    log.warn("TunneledPktArpCallback.call: dropping malformed "
                            + "packet from port {}.  {}", inPort, ex.getMessage());
                    freeBuffer(bufferId);
                    return;
                }

                sendICMPforTunneledPkt(ICMP.UNREACH_CODE.UNREACH_HOST, ethPkt,
                        portsAndGw.lastIngressPortId,
                        portsAndGw.lastEgressPortId);
            }
        }
    }

    private void addFlowAndSendPacket(int bufferId, OFMatch match,
            short idleTimeoutSecs, short hardTimeoutSecs,
            boolean sendFlowRemove, List<OFAction> actions, int inPort,
            byte[] data) {
        controllerStub.sendFlowModAdd(match, 0, idleTimeoutSecs,
                hardTimeoutSecs, FLOW_PRIORITY, bufferId, sendFlowRemove,
                false, false, actions);
        // If packet was unbuffered, we need to explicitly send it otherwise the
        // flow won't be applied to it.
        if (bufferId == ControllerStub.UNBUFFERED_ID)
            controllerStub.sendPacketOut(bufferId, OFPort.OFPP_NONE.getValue(),
                    actions, data);
    }

    private class LocalPktArpCallback implements Callback<MAC> {
        public LocalPktArpCallback(int bufferId, int totalLen,
                L3DevicePort devPortIn, byte[] data, MidoMatch match,
                ForwardInfo fwdInfo, Ethernet ethPkt, Set<UUID> traversedRouters) {
            super();
            this.bufferId = bufferId;
            this.totalLen = totalLen;
            this.inPort = devPortIn;
            this.data = data;
            this.match = match;
            this.fwdInfo = fwdInfo;
            this.ethPkt = ethPkt;
            this.traversedRouters = traversedRouters;
        }

        int bufferId;
        int totalLen;
        L3DevicePort inPort;
        byte[] data;
        MidoMatch match;
        ForwardInfo fwdInfo;
        Ethernet ethPkt;
        Set<UUID> traversedRouters;

        @Override
        public void call(MAC mac) {
            String nwDstStr = IPv4.fromIPv4Address(match
                    .getNetworkDestination());
            if (null != mac) {
                log.debug("LocalPktArpCallback.call: mac resolved for local packet to {}", nwDstStr);

                L3DevicePort devPort = devPortById.get(fwdInfo.outPortId);
                if (null == devPort) {
                    log.warn("LocalPktArpCallback.call: port is no longer local");
                    freeFlowResources(match, traversedRouters);
                    freeBuffer(bufferId);
                    // TODO(pino): do we need to do anything for this?
                    // The port was removed while we waited for the ARP.
                    return;
                }
                log.debug("LocalPktArpCallback.call: forward and install flow");
                
                fwdInfo.matchOut.setDataLayerSource(devPort.getMacAddr());
                fwdInfo.matchOut.setDataLayerDestination(mac);
                List<OFAction> ofActions = makeActionsForFlow(match,
                        fwdInfo.matchOut, devPort.getNum());
                boolean useWildcards = false; // TODO: get this from config.
                if (useWildcards) {
                    // TODO: Should we check for non-load-balanced routes and
                    // wild-card flows matching them on layer 3 and lower?
                    // inPort, dlType, nwSrc.
                    match = makeWildcarded(match);
                }
                // Track the routers for this flow so we can free resources
                // when the flow is removed.
                matchToRouters.put(match, traversedRouters);
                addFlowAndSendPacket(bufferId, match, idleFlowExpireSeconds,
                        NO_HARD_TIMEOUT, true, ofActions, inPort.getNum(),
                        data);
            } else {
                log.debug("ARP timed out for local packet to {} - send ICMP",
                        nwDstStr);
                installBlackhole(match, bufferId, NO_IDLE_TIMEOUT,
                        ICMP_EXPIRY_SECONDS);
                freeFlowResources(match, traversedRouters);
                // Send an ICMP !H
                sendICMPforLocalPkt(ICMP.UNREACH_CODE.UNREACH_HOST, inPort
                        .getId(), ethPkt, fwdInfo.inPortId, fwdInfo.pktIn,
                        fwdInfo.outPortId);
            }
            notifyFlowAdded(match, fwdInfo.matchOut, inPort.getId(), fwdInfo,
                    traversedRouters);
        }
    }

    /**
     * Send an ICMP Unreachable for a packet that arrived on a materialized port
     * that is not local to this controller. Equivalently, the packet was
     * received over a tunnel.
     * 
     * @param unreachCode
     *            The ICMP error code of ICMP Unreachable sent by this method.
     * @param tunneledEthPkt
     *            The original packet as it was received over the tunnel.
     * @param lastIngress
     *            The ingress port of the last router that handled the packet.
     * @param lastEgress
     *            The port from which the last router would have emitted the
     *            packet if it hadn't triggered an ICMP.
     */
    private void sendICMPforTunneledPkt(ICMP.UNREACH_CODE unreachCode,
            Ethernet tunneledEthPkt, UUID lastIngress, UUID lastEgress) {
        /*
         * We have a lot less information in the tunneled case compared to
         * non-tunneled packets. We only have the packet as it would have been
         * emitted by the egress port, not as it was seen at the ingress port of
         * the last router that handled the packet. That makes it hard to: 1)
         * correctly build the ICMP packet. 2) determine the materialized egress
         * port for the ICMP message. This will require invoking the routing
         * logic. 3) determine the next-hop gateway data link address. This
         * requires invoking the routing logic and then ARPing the next hop
         * gateway network address.
         */

        // The packet came over a tunnel so its mac addresses were used
        // to encode Midonet port ids. Set the mac addresses to make
        // sure they don't run afoul of the ICMP error rules.
        tunneledEthPkt.setDestinationMACAddress(MAC.fromString("02:00:00:33:44:55"));
        tunneledEthPkt.setSourceMACAddress(MAC.fromString("02:00:00:33:44:55"));
        if (!canSendICMP(tunneledEthPkt, lastEgress)) {
            log.debug("sendICMPforTunneledPkt: cannot send ICMP");
            return;
        }

        // First, we ask the last router to undo any transformation it may have
        // applied on the packet.
        network.undoRouterTransformation(tunneledEthPkt);
        ICMP icmp = new ICMP();
        IPv4 ipPktAtEgress = IPv4.class.cast(tunneledEthPkt.getPayload());
        icmp.setUnreachable(unreachCode, ipPktAtEgress);
        // The icmp packet will be emitted from the lastIngress port.
        IPv4 ip = new IPv4();
        PortDirectory.RouterPortConfig portConfig;
        try {
            portConfig = network.getPortConfig(lastIngress);
        } catch (Exception e) {
            // Can't send the ICMP if we can't find the last egress port.
            return;
        }
        ip.setSourceAddress(portConfig.portAddr);
        ip.setDestinationAddress(ipPktAtEgress.getSourceAddress());
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setPayload(icmp);
        Ethernet eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        // Use fictitious mac addresses before routing.
        eth.setSourceMACAddress(MAC.fromString("02:a1:b2:c3:d4:e5"));
        eth.setDestinationMACAddress(MAC.fromString("02:a1:b2:c3:d4:e6"));
        byte[] data = eth.serialize();
        MidoMatch match = new MidoMatch();
        match.loadFromPacket(data, (short) 0);
        ForwardInfo fwdInfo = new ForwardInfo();
        fwdInfo.flowMatch = match;
        fwdInfo.matchIn = match.clone();
        fwdInfo.pktIn = eth;
        if (portConfig instanceof PortDirectory.MaterializedRouterPortConfig) {
            // The lastIngress port is materialized. Invoke the routing logic of
            // its router in order to find the network address of the next hop
            // gateway for the packet. Hopefully, the routing logic will agree
            // that the lastIngress port should emit the ICMP.
            Router rtr;
            try {
                rtr = network.getRouterByPort(lastIngress);
            } catch (Exception e) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward because we failed to retrieve the router "
                        + "that would route it.");
                return;
            }
            rtr.process(fwdInfo);
            if (!fwdInfo.action.equals(Action.FORWARD)) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward it because router.process didn't return "
                        + "FORWARD action.");
                return;
            }
            if (!fwdInfo.outPortId.equals(lastIngress)) {
                log.warn("Dropping ICMP error message. Would be emitted from"
                        + "a materialized port different from the materialized"
                        + "ingress port of the original packet.");
                return;
            }
        } else {
            // The lastIngress port is logical. Invoke the routing logic of the
            // router network, starting from the lastIngress's peer port.
            // Set the ICMP messages dl addresses to bogus addresses for
            // routing.
            Set<UUID> routerIds = new HashSet<UUID>();
            UUID peer_uuid = ((PortDirectory.LogicalRouterPortConfig) portConfig).peer_uuid;
            fwdInfo.inPortId = peer_uuid;
            try {
                network.process(fwdInfo, routerIds);
            } catch (Exception e) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward it because network.process threw exception.");
                return;
            }
            if (!fwdInfo.action.equals(Action.FORWARD)) {
                log.warn("Dropping ICMP error message. Don't know where to "
                        + "forward it because network.process didn't return "
                        + "FORWARD action.");
                return;
            }
        }
        // TODO(pino): if the match changed, apply the changes to the packet.
        L3DevicePort devPort = devPortById.get(fwdInfo.outPortId);
        if (null != devPort) {
            // The packet came over the tunnel, but the ICMP is being sent to
            // a local port? This should be rare, let's log it and drop it.
            log.warn("Dropping ICMP error message. Original packet came from "
                    + "a tunnel, but the ICMP would be emitted from a local port");
            return;
        }
        // The ICMP will be tunneled. Encode the outPortId and next hop gateway
        // network address in the ethernet packet's address fields. For non-icmp
        // packets we usually encode the inPortId too, but it's only needed to
        // generate ICMPs so in this case it isn't needed. Instead we'll encode
        // a special value so the other end of the tunnel can recognize this
        // as a tunneled ICMP.
        MAC[] dlHeaders = getDlHeadersForTunnel(
                ICMP_TUNNEL,
                ShortUUID.UUID32toInt(fwdInfo.outPortId),
                fwdInfo.nextHopNwAddr);
        
        eth.setSourceMACAddress(dlHeaders[0]);
        eth.setDestinationMACAddress(dlHeaders[1]);

        Integer tunNum = super.portUuidToTunnelPortNumber(fwdInfo.outPortId);
        if (null == tunNum) {
            log.warn("Dropping ICMP error message. Can't find tunnel to peer"
                    + "port.");
            return;
        }
        sendUnbufferedPacketFromPort(eth, tunNum.shortValue());
    }

    /**
     * Send an ICMP Unreachable for a packet that arrived on a materialized port
     * local to this controller.
     * 
     * @param unreachCode
     *            The ICMP error code of ICMP Unreachable sent by this method.
     * @param firstIngress
     *            The materialized port that received the original packet that
     *            entered the router network.
     * @param pktAtFirstIngress
     *            The original packet as seen by the firtIngress port.
     * @param lastIngress
     *            The ingress port of the last router that handled the packet.
     *            May be equal to the firstIngress, but could also be a logical
     *            port on a different router than firstIngress.
     * @param pktAtLastIngress
     *            The original packet as seen when it first entered the last
     *            router that handled it (and which triggered the ICMP).
     * @param lastEgress
     *            The port from which the last router would have emitted the
     *            packet if it hadn't triggered an ICMP. Null if it isn't known.
     */
    private void sendICMPforLocalPkt(ICMP.UNREACH_CODE unreachCode,
            UUID firstIngress, Ethernet pktAtFirstIngress, UUID lastIngress,
            Ethernet pktAtLastIngress, UUID lastEgress) {
        // Use the packet as seen by the last router to decide whether it's ok
        // to send an ICMP error message.
        if (!canSendICMP(pktAtLastIngress, lastEgress))
            return;
        // Build the ICMP as it would be built by the last router that handled
        // the original packet.
        ICMP icmp = new ICMP();
        icmp.setUnreachable(unreachCode, IPv4.class.cast(pktAtLastIngress
                .getPayload()));
        // The following ip packet to route the ICMP to its destination.
        IPv4 ip = new IPv4();
        // The packet's network address is that of the last ingress port.
        PortDirectory.RouterPortConfig portConfig;
        try {
            portConfig = network.getPortConfig(lastIngress);
        } catch (Exception e) {
            // Can't send the ICMP if we can't find the last ingress port.
            return;
        }
        // TODO(pino): what do we do if this isn't a public/global address?
        ip.setSourceAddress(portConfig.portAddr);
        // At this point, we should be using the source network address
        // from the ICMP payload as the ICMP's destination network address.
        // Then we'd have to inject the ICMP message into that last router and
        // allow it to invoke its routing logic. Instead, we cut corners here
        // and avoid the routing logic by inverting the fields in the original
        // packet as seen by the first ingress port.
        IPv4 ipPktAtFirstIngress = IPv4.class.cast(pktAtFirstIngress
                .getPayload());
        ip.setDestinationAddress(ipPktAtFirstIngress.getSourceAddress());
        ip.setProtocol(ICMP.PROTOCOL_NUMBER);
        ip.setPayload(icmp);
        // The following is the Ethernet packet for the ICMP message.
        Ethernet eth = new Ethernet();
        eth.setEtherType(IPv4.ETHERTYPE);
        eth.setPayload(ip);
        L3DevicePort firstIngressDevPort = devPortById.get(firstIngress);
        if (null == firstIngressDevPort) {
            // Can't send the ICMP if we no longer have the first ingress port.
            return;
        }
        eth.setSourceMACAddress(firstIngressDevPort.getMacAddr());
        eth.setDestinationMACAddress(pktAtFirstIngress.getSourceMACAddress());
        log.debug("sendICMPforLocalPkt from OFport {}, {} to {}", new Object[] {
                firstIngressDevPort.getNum(),
                IPv4.fromIPv4Address(ip.getSourceAddress()),
                IPv4.fromIPv4Address(ip.getDestinationAddress()) });
        sendUnbufferedPacketFromPort(eth, firstIngressDevPort.getNum());
    }

    private void sendUnbufferedPacketFromPort(Ethernet ethPkt, short portNum) {
        OFActionOutput action = new OFActionOutput(portNum, (short) 0);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(action);
        log.debug("sendUnbufferedPacketFromPort {}", ethPkt);
        controllerStub.sendPacketOut(ControllerStub.UNBUFFERED_ID,
                OFPort.OFPP_NONE.getValue(), actions, ethPkt.serialize());
    }

    /**
     * Determine whether a packet can trigger an ICMP error. Per RFC 1812 sec.
     * 4.3.2.7, some packets should not trigger ICMP errors: 1) Other ICMP
     * errors. 2) Invalid IP packets. 3) Destined to IP bcast or mcast address.
     * 4) Destined to a link-layer bcast or mcast. 5) With source network prefix
     * zero or invalid source. 6) Second and later IP fragments.
     * 
     * @param ethPkt
     *            We wish to know whether this packet may trigger an ICMP error
     *            message.
     * @param egressPortId
     *            If known, this is the port that would have emitted the packet.
     *            It's used to determine whether the packet was addressed to an
     *            IP (local subnet) broadcast address.
     * @return True if-and-only-if the packet meets none of the above conditions
     *         - i.e. it can trigger an ICMP error message.
     */
    boolean canSendICMP(Ethernet ethPkt, UUID egressPortId) {
        if (ethPkt.getEtherType() != IPv4.ETHERTYPE)
            return false;
        IPv4 ipPkt = IPv4.class.cast(ethPkt.getPayload());
        // Ignore ICMP errors.
        if (ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ICMP icmpPkt = ICMP.class.cast(ipPkt.getPayload());
            if (icmpPkt.isError()) {
                log.debug("Don't generate ICMP Unreachable for other ICMP "
                        + "errors.");
                return false;
            }
        }
        // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
        // Ignore packets to IP mcast addresses.
        if (ipPkt.isMcast()) {
            log.debug("Don't generate ICMP Unreachable for packets to an IP "
                    + "multicast address.");
            return false;
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != egressPortId) {
            PortDirectory.RouterPortConfig portConfig;
            try {
                portConfig = network.getPortConfig(egressPortId);
            } catch (Exception e) {
                return false;
            }
            if (ipPkt.isSubnetBcast(portConfig.nwAddr, portConfig.nwLength)) {
                log.debug("Don't generate ICMP Unreachable for packets to "
                        + "the subnet local broadcast address.");
                return false;
            }
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast()) {
            log.debug("Don't generate ICMP Unreachable for packets to "
                    + "Ethernet broadcast or multicast address.");
            return false;
        }
        // Ignore packets with source network prefix zero or invalid source.
        // TODO(pino): See RFC 1812 sec. 5.3.7
        if (ipPkt.getSourceAddress() == 0xffffffff
                || ipPkt.getDestinationAddress() == 0xffffffff) {
            log.debug("Don't generate ICMP Unreachable for all-hosts broadcast "
                    + "packet");
            return false;
        }
        // TODO(pino): check this fragment offset
        // Ignore datagram fragments other than the first one.
        if (0 != (ipPkt.getFragmentOffset() & 0x1fff)) {
            log.debug("Don't generate ICMP Unreachable for IP fragment packet");
            return false;
        }
        return true;
    }

    private void notifyFlowAdded(MidoMatch origMatch, MidoMatch flowMatch,
            UUID inPortId, ForwardInfo fwdInfo, Set<UUID> routers) {
        // TODO Auto-generated method stub

    }

    private void installBlackhole(MidoMatch flowMatch, int bufferId,
            short idleTimeout, short hardTimeout) {
        // TODO(pino): can we just send a null list instead of an empty list?
        List<OFAction> actions = new ArrayList<OFAction>();
        controllerStub.sendFlowModAdd(flowMatch, (long) 0, idleTimeout,
                hardTimeout, (short) 0, bufferId, true, false, false,
                actions);
        // Note that if the packet was buffered, then the datapath will apply
        // the flow and drop it. If the packet was unbuffered, we don't need
        // to do anything.
    }

    private MidoMatch makeWildcarded(MidoMatch origMatch) {
        // TODO Auto-generated method stub
        return origMatch;
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount, long matchingTunnelId) {
        log.debug("onFlowRemoved: match {} reason {}", match, reason);

        // TODO(pino): do we care why the flow was removed?
        Collection<UUID> routers = matchToRouters.get(match);
        if (null != routers) {
            log.debug("onFlowRemoved: found routers {} for match {}", routers, match);
            freeFlowResources(match, routers);
        }
    }

    public void freeFlowResources(OFMatch match, Collection<UUID> routers) {
        for (UUID rtrId : routers) {
            try {
                Router rtr = network.getRouter(rtrId);
                rtr.freeFlowResources(match);
            } catch (ZkStateSerializationException e) {
                log.warn("freeFlowResources failed for match {} in router {} -"
                        + " caught: \n{}",
                        new Object[] { match, rtrId, e.getStackTrace() });
            } catch (StateAccessException e) {
                log.warn("freeFlowResources failed for match {} in router {} -"
                        + " caught: \n{}",
                        new Object[] { match, rtrId, e.getStackTrace() });
            } catch (JMException e) {
                log.warn("freeFlowResources failed for match {} in router {} -"
                        + " caught: \n{}",
                        new Object[] { match, rtrId, e.getStackTrace() });
            }
        }
    }

    @Override
    public void setServiceFlows(short localPortNum, short remotePortNum,
            int localAddr, int remoteAddr, short localTport, short remoteTport) {
        // Remember service's target port assuming that service flows sent
        // this packet to the OFPP_CONTROLLER.
        // TODO(yoshi): replace this with better mechanism such as ARP proxy
        // for service ports.
        serviceTargetPort = remotePortNum;

        // local to remote.
        MidoMatch match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportDestination(remoteTport);
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        // OFPP_NONE is placed since outPort should be ignored. cf. OpenFlow
        // specification 1.0 p.15.
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
            match.setNetworkSource(localAddr);
        match.setNetworkDestination(remoteAddr);
        match.setTransportSource(localTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // remote to local.
        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportDestination(localTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(TCP.PROTOCOL_NUMBER);
        match.setNetworkSource(remoteAddr);
        match.setNetworkDestination(localAddr);
        match.setTransportSource(remoteTport);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // ARP flows.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(ARP.ETHERTYPE);
        // Output to both service port and controller port. Output to
        // OFPP_CONTROLLER requires to set non-zero value to max_len, and we
        // are setting the standard max_len (128 bytes) in OpenFlow.
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        actions.add(new OFActionOutput(OFPort.OFPP_CONTROLLER.getValue(),
                (short) 128));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        // ICMP flows.
        // Only valid for the service port with specified address.
        match = new MidoMatch();
        match.setInputPort(localPortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkSource(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(remotePortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);

        match = new MidoMatch();
        match.setInputPort(remotePortNum);
        match.setDataLayerType(IPv4.ETHERTYPE);
        match.setNetworkProtocol(ICMP.PROTOCOL_NUMBER);
        match.setNetworkDestination(localAddr);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(localPortNum, (short) 0));
        controllerStub.sendFlowModAdd(match, 0, NO_IDLE_TIMEOUT,
                NO_HARD_TIMEOUT, SERVICE_FLOW_PRIORITY,
                ControllerStub.UNBUFFERED_ID, false, false, false, actions);
    }

    private void startPortService(final short portNum, final UUID portId)
            throws KeeperException, InterruptedException,
            ZkStateSerializationException, IOException, StateAccessException {
        // If the materiazlied router port isn't discovered yet, try
        // setting flows between BGP peers later.
        if (devPortById.containsKey(portId)) {
            service.start(datapathId, portNum,
                          devPortById.get(portId).getNum());
        } else {
            if (!portServicesById.containsKey(portId)) {
                portServicesById.put(portId, new ArrayList<Runnable>());
            }
            List<Runnable> watchers = portServicesById.get(portId);
            watchers.add(new Runnable() {
                public void run() {
                    try {
                        service.start(datapathId, portNum,
                                      devPortById.get(portId).getNum());
                    } catch (Exception e) {
                        log.warn("startPortService", e);
                    }
                }
            });
        }
    }

    private void setupServicePort(int portNum, String portName)
            throws StateAccessException, ZkStateSerializationException,
            IOException, KeeperException, InterruptedException {
        UUID portId = service.getRemotePort(portName);
        if (portId != null) {
            service.configurePort(portId, portName);
            startPortService((short)portNum, portId);
        }
    }

    private void addServicePort(L3DevicePort port) throws StateAccessException,
            ZkStateSerializationException, KeeperException {
        Set<String> servicePorts = service.getPorts(port.getId());
        if (!servicePorts.isEmpty()) {
            UUID portId = port.getId();
            if (portServicesById.containsKey(portId)) {
                for (Runnable watcher : portServicesById.get(portId)) {
                    watcher.run();
                }
                return;
            }
        }
        service.addPort(datapathId, port.getId(), port.getMacAddr());
    }

    @Override
    protected void portMoved(UUID portUuid, IntIPv4 oldAddr, IntIPv4 newAddr) {
        // Do nothing.
    }

    @Override
    public final void clear() {
        // Do nothing.
    }

    @Override
    protected void addVirtualPort(int portNum, String name, MAC addr,
            UUID portId) {
        L3DevicePort devPort = devPortByNum.get(portNum);
        if (null != devPort) {
            log.error("addVirtualPort num:{} name:{} was already added.",
                    portNum, name);
            return;
        }

        try {
            devPort = new L3DevicePort(portMgr, routeMgr, portId,
                    (short)portNum, addr, super.controllerStub);
        } catch (Exception e) {
            log.error("addVirtualPort", e);
            return;
        }
        devPortById.put(portId, devPort);
        devPortByNum.put(portNum, devPort);

        log.info("addVirtualPort number {} bound to vport {} with "
                + "nw address {}", new Object[] { portNum, devPort.getId(),
                IPv4.fromIPv4Address(devPort.getVirtualConfig().portAddr) });
        try {
            network.addPort(devPort);
            addServicePort(devPort);
        } catch (Exception e) {
            log.error("addVirtualPort", e);
        }
        setFlowsForHandlingDhcpInController(devPort);
    }

    @Override
    protected void deleteVirtualPort(int portNum, UUID portId) {
        L3DevicePort devPort = devPortByNum.get(portNum);
        if (null == devPort) {
            log.error("deleteVirtualPort num:{} uuid:{} was never added.",
                    portNum, portId);
            return;
        }
        // TODO(pino): should we check that the devPort's uuid == portId?
        log.info("deletePort number {} bound to virtual port {} with "
                + "nw address {}", new Object[] { devPort.getNum(),
                portId, devPort.getVirtualConfig().portAddr });
        try {
            network.removePort(devPort);
        } catch (Exception e) {
            log.error("deleteVirtualPort", e);
        }
        devPortById.remove(portId);
        devPortByNum.remove(portNum);
    }

    @Override
    protected void addServicePort(int num, String name, UUID vId) {
        try {
            setupServicePort(num, name);
        } catch (Exception e) {
            log.error("addServicePort", e);
        }
    }

    @Override
    protected void deleteServicePort(int num, String name, UUID vId) {
        // TODO: handle the removal of a service port.
    }

    @Override
    protected void addTunnelPort(int num, IntIPv4 peerIP) {
        // Do nothing.
    }

    @Override
    protected void deleteTunnelPort(int num, IntIPv4 peerIP) {
        // Do nothing.
    }

}
