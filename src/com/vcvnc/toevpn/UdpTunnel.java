package com.vcvnc.toevpn;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class UdpTunnel {
	private MyConnect connect = null;
	private boolean isClose = false;
	private ArrayList<UdpProxy> udpProxys = new ArrayList<>();
	public static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.UDP_HEADER_SIZE;

	public UdpTunnel(MyConnect connect) {
		this.connect = connect;
	}

	public void close() {
		isClose = true;
		for (UdpProxy udpProxy : udpProxys) {
			udpProxy.close();
		}
	}

	public boolean isClose() {
		return isClose;
	}

	public void processPacket(byte[] bytes, int offset, int size) throws UnknownHostException {
		if (udpProxys.size() > Config.MAX_CONNECT) {
			System.out.println("UdpProxy max");
			clearExpireProxy();
		}
		ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, 0, size);
		Packet packet = new Packet(byteBuffer);
		InetAddress srcIp = packet.getIp4Header().sourceAddress;
		InetAddress destIp = packet.getIp4Header().destinationAddress;
		int srcPort = packet.getUdpHeader().sourcePort;
		int destPort = packet.getUdpHeader().destinationPort;

		int dataSize = size - HEADER_SIZE;
		byte[] data = new byte[dataSize];
		System.arraycopy(bytes, offset + HEADER_SIZE, data, 0, dataSize);
		DatagramPacket clientPacket = new DatagramPacket(data, dataSize, destIp, destPort);

		UdpProxy proxy = null;
		for (int i = 0; i < udpProxys.size(); i++) {
			if (udpProxys.get(i).srcIp.equals(srcIp) && udpProxys.get(i).destIp.equals(destIp)
					&& udpProxys.get(i).srcPort == srcPort && udpProxys.get(i).destPort == destPort) {
				proxy = udpProxys.get(i);
				break;
			}
		}

		if (proxy == null) {
			proxy = new UdpProxy(connect, packet, srcIp, destIp, srcPort, destPort);
			udpProxys.add(proxy);
		}
		proxy.sendToServer(clientPacket);

	}

	public void clearExpireProxy() {
		synchronized (udpProxys) {
			for (int i = 0; i < udpProxys.size(); i++) {
				if ((System.currentTimeMillis() - udpProxys.get(i).lastRefreshTime) > 120000) {
					udpProxys.get(i).close();
					udpProxys.remove(i);
					i--;
				}
			}
		}
	}

	public void clearAllProxy() {
		synchronized (udpProxys) {
			for (UdpProxy udpProxy : udpProxys) {
				udpProxy.close();
			}
			udpProxys.clear();
		}
	}
}
