package com.vcvnc.toevpn;

import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class TcpTunnel {
	private MyConnect connect = null;
	private ArrayList<TcpProxy> tcpProxys = new ArrayList<TcpProxy>();
	public static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;
	private static final byte FIN_ACK = Packet.TCPHeader.FIN | Packet.TCPHeader.ACK;
	private static final byte FIN_PSH_ACK = Packet.TCPHeader.FIN | Packet.TCPHeader.PSH | Packet.TCPHeader.ACK;
	private static final byte RST_ACK = Packet.TCPHeader.RST | Packet.TCPHeader.ACK;
	private static final byte PSH_ACK = Packet.TCPHeader.PSH | Packet.TCPHeader.ACK;

	public TcpTunnel(MyConnect connect) {
		this.connect = connect;
	}
	
	public void processPacket(byte[] bytes, int offset, int size) throws UnknownHostException {
		if (tcpProxys.size() > Config.MAX_CONNECT) {
			System.out.println("TcpProxy max");
			clearExpireProxy();
		}
		ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, offset, size);
		Packet packet = new Packet(byteBuffer);

		//System.out.println(packet);

		TcpProxy tcpProxy = null;
		// 找到代理
		for (int i = 0; i < tcpProxys.size(); i++) {
			if (equalSocket(tcpProxys.get(i).fistPacket, packet)) {
				tcpProxy = tcpProxys.get(i);
			}
		}

		switch (packet.getTcpHeader().flags) {
		case Packet.TCPHeader.SYN:
			handleSYN(packet, tcpProxy);
			break;
		case Packet.TCPHeader.FIN:
			handleFIN(packet, tcpProxy);
			break;
		case Packet.TCPHeader.ACK:
			handleACK(packet, tcpProxy);
			break;
		case FIN_ACK:
			handleFIN_ACK(packet, tcpProxy);
			break;
		case PSH_ACK:
			handlePSH_ACK(packet, tcpProxy);
			break;
		case FIN_PSH_ACK:
			handleFIN_PSH_ACK(packet, tcpProxy);
		case RST_ACK:
			handleRST_ACK(packet, tcpProxy);
			break;
		case Packet.TCPHeader.RST:
			handleRST(packet, tcpProxy);
			break;
		case Packet.TCPHeader.PSH:
			handlePSH(packet, tcpProxy);
			break;
		case Packet.TCPHeader.URG:
			handleURG(packet, tcpProxy);
			break;
		default:
			System.out.println("default " + packet.getTcpHeader().flags);
			break;
		}

	}

	public void handleSYN(Packet packet, TcpProxy tcpProxy) throws UnknownHostException {
		// System.out.println("SYN flag");
		if (tcpProxy == null) { // 没有建立连接
			tcpProxy = new TcpProxy(connect, packet);
			tcpProxys.add(tcpProxy);
			tcpProxy.processSYNPacket(packet);
		} else {
			tcpProxy.close();
		}

	}

	public void handleACK(Packet packet, TcpProxy tcpProxy) {

		if (tcpProxy == null) { // 没有建立连接
			// System.out.println("ACK NOT IN");
		} else {
			// System.out.println("ACK flag");
			tcpProxy.processACKPacket(packet);
		}
	}

	public void handleFIN(Packet packet, TcpProxy tcpProxy) {

		if (tcpProxy == null) {
			System.out.println("FIN NOT IN");
		} else {
			System.out.println("FIN flag");
			tcpProxy.processFINPacket(packet);
		}
	}

	public void handleFIN_ACK(Packet packet, TcpProxy tcpProxy) {
		if (tcpProxy == null) {
			// System.out.println("FIN_ACK NOT IN");
			packet.swapSourceAndDestination();
			packet.getTcpHeader().headerLength = 20;
			packet.ip4Header.totalLength = TcpTunnel.HEADER_SIZE;
			packet.getIp4Header().identificationAndFlagsAndFragmentOffset = 0;
			byte flags = (byte) (Packet.TCPHeader.ACK);

			long seq = packet.getTcpHeader().acknowledgementNumber;
			long ack = packet.getTcpHeader().sequenceNumber + 1;
			packet.updateTCPBuffer(packet.backingBuffer, flags, seq, ack, 0);

			// 往vpn客户端写ACK回复帧
			connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);

		} else {
			// System.out.println("FIN_ACK flag");
			tcpProxy.processFINPacket(packet);
			tcpProxy.processESTABLISHEDACKPacket(packet);
		}
	}

	public void handleFIN_PSH_ACK(Packet packet, TcpProxy tcpProxy) {
		if (tcpProxy == null) {
			// System.out.println("FIN_PSH_ACK NOT IN");
			packet.swapSourceAndDestination();
			packet.getTcpHeader().headerLength = 20;
			packet.ip4Header.totalLength = TcpTunnel.HEADER_SIZE;
			packet.getIp4Header().identificationAndFlagsAndFragmentOffset = 0;
			byte flags = (byte) (Packet.TCPHeader.ACK);

			long seq = packet.getTcpHeader().acknowledgementNumber;
			long ack = packet.getTcpHeader().sequenceNumber + 1;
			packet.updateTCPBuffer(packet.backingBuffer, flags, seq, ack, 0);

			// 往vpn客户端写ACK回复帧
			connect.sendToClient(packet.backingBuffer.array(), 0, TcpTunnel.HEADER_SIZE);
		} else {
			// System.out.println("FIN_PSH_ACK flag");
			tcpProxy.processFINPacket(packet);
			tcpProxy.processESTABLISHEDACKPacket(packet);
		}
	}

	public void handleRST_ACK(Packet packet, TcpProxy tcpProxy) {
		// System.out.println(packet);
		if (tcpProxy == null) {
			// System.out.println("RST_ACK NOT IN");
		} else {
			tcpProxys.remove(tcpProxy);
			tcpProxy.processESTABLISHEDACKPacket(packet);
			tcpProxy.close();
			// System.out.println("RST_ACK flag");
		}
	}
	
	public void handlePSH_ACK(Packet packet, TcpProxy tcpProxy) {
		// System.out.println(packet);
		if (tcpProxy == null) { // 没有建立连接
			// System.out.println("PSH_ACK NOT IN");
		} else {
			//System.out.println("PSH_ACK flag");
			tcpProxy.processACKPacket(packet);
		}
	}

	public void handleRST(Packet packet, TcpProxy tcpProxy) {
		// System.out.println(packet);
		if (tcpProxy == null) {
			// System.out.println("RST NOT IN");
		} else {
			tcpProxys.remove(tcpProxy);
			tcpProxy.close();
			// System.out.println("RST flag");
		}
	}

	public void handlePSH(Packet packet, TcpProxy tcpProxy) {

		if (tcpProxy == null) {
			// System.out.println("PSH NOT IN");
		} else {
			// System.out.println("PSH flag");
			tcpProxy.processESTABLISHEDACKPacket(packet);
		}
	}

	public void handleURG(Packet packet, TcpProxy tcpProxy) {

		if (tcpProxy == null) {
			//System.out.println("UGG NOT IN");
		} else {
			System.out.println("URG flag");
			//
			tcpProxy.close();
		}
	}

	public boolean equalSocket(Packet packet1, Packet packet2) {
		return packet1.getIp4Header().destinationAddress.equals(packet2.getIp4Header().destinationAddress)
				&& packet1.getIp4Header().sourceAddress.equals(packet2.getIp4Header().sourceAddress)
				&& packet1.getTcpHeader().destinationPort == packet2.getTcpHeader().destinationPort
				&& packet1.getTcpHeader().sourcePort == packet2.getTcpHeader().sourcePort;

	}
	public void clearExpireProxy() {
		synchronized (tcpProxys) {
			for (int i = 0; i < tcpProxys.size(); i++) {
				if ((System.currentTimeMillis() - tcpProxys.get(i).lastRefreshTime) > 120000) {
					tcpProxys.get(i).close();
					tcpProxys.remove(i);
					i--;
				}
			}
		}
	}
	public void clearAllProxy() {
		for (int i = 0; i < tcpProxys.size(); i++) {
			tcpProxys.get(i).close();
		}
		tcpProxys.clear();
	}

}
