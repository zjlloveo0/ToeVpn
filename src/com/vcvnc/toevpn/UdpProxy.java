package com.vcvnc.toevpn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class UdpProxy implements Runnable {
	private DatagramSocket datagramSocket;
	private Packet packet;
	private boolean isClose = false;
	public InetAddress ip;
	public int port;
	public InetAddress srcIp;
	public int srcPort;
	public InetAddress destIp;
	public int destPort;
	public MyConnect connect;
	Thread thread = null;
	public long lastRefreshTime;

	public UdpProxy(MyConnect connect, Packet packet, InetAddress srcIp, InetAddress destIp, int srcPort,
			int destPort) {
		try {
			datagramSocket = new DatagramSocket();
			packet.swapSourceAndDestination();
			this.connect = connect;
			this.packet = packet;
			this.srcIp = srcIp;
			this.destIp = destIp;
			this.srcPort = srcPort;
			this.destPort = destPort;

			thread = new Thread(this);
			thread.start();

		} catch (SocketException e) {
			close();
			e.printStackTrace();
		}
	}

	public void sendToServer(DatagramPacket sendPacket) {
		this.lastRefreshTime = System.currentTimeMillis();
		try {
			datagramSocket.send(sendPacket);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			close();
			e.printStackTrace();
		}
	}

	public void close() {
		isClose = true;
		if (thread != null)
			thread.interrupt();
		if (datagramSocket != null) {
			datagramSocket.close();
		}
	}

	public boolean isClose() {
		return datagramSocket.isClosed() || isClose;
	}

	@Override
	public void run() {
		// TODO Auto-generated method stub
		while (!isClose()) {
			byte[] revBuf = new byte[Config.MUTE - UdpTunnel.HEADER_SIZE];
			DatagramPacket revPacket = new DatagramPacket(revBuf, revBuf.length);
			try {
				datagramSocket.receive(revPacket);
				int len = revPacket.getLength();
				if (len > 0) {
					byte[] dataCopy = new byte[UdpTunnel.HEADER_SIZE + len];
					System.arraycopy(revPacket.getData(), 0, dataCopy, UdpTunnel.HEADER_SIZE, len);
					ByteBuffer buf = ByteBuffer.wrap(dataCopy);
					Packet newPacket = packet.duplicated();
					// note udp checksum = 0;
					newPacket.updateUDPBuffer(buf, len);
					// System.out.println("send to client data: "+newPacket);

					connect.sendToClient(dataCopy, 0, UdpTunnel.HEADER_SIZE + len);
				}
				lastRefreshTime = System.currentTimeMillis();

			} catch (IOException e) {
				// TODO Auto-generated catch block
				close();
				// e.printStackTrace();
			}
		}
	}

}
