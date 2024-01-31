package com.vcvnc.toevpn;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;

public class MyConnect implements Runnable {
	private Socket socket;
	private InputStream is;
	private OutputStream os;
	private byte[] cacheBytes;
	private boolean haveCacheBytes;
	private TcpTunnel tcpTunnel = null;
	private UdpTunnel udpTunnel = null;
	private boolean isClose = false;
	private Thread thread;

	public MyConnect(Socket socket) {
		this.socket = socket;
		tcpTunnel = new TcpTunnel(this);
		udpTunnel = new UdpTunnel(this);
		try {
			is = socket.getInputStream();
			os = socket.getOutputStream();
			thread = new Thread(this);
			thread.start();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void close() {
		isClose = true;
		try {
			if(tcpTunnel != null )tcpTunnel.clearAllProxy();
			if(udpTunnel != null )udpTunnel.clearAllProxy();
			is.close();
			os.close();
			socket.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public boolean isClose() {
		return socket.isClosed() || isClose;
	}

	public void sendToClient(byte[] packet, int offset, int size) {
		try {
			os.write(packet, offset, size);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			close();
			e.printStackTrace();
		}
	}
	
	public synchronized void processRecvPacket(byte[] bytes, int size) throws UnknownHostException {
		if (this.haveCacheBytes) {
			byte[] data = new byte[this.cacheBytes.length + size];
			System.arraycopy(this.cacheBytes, 0, data, 0, this.cacheBytes.length);
			System.arraycopy(bytes, 0, data, this.cacheBytes.length, size);
			bytes = data;

			// System.out.println("recv size: "+size + " cache size: "+this.cacheBytes.length);
			size = this.cacheBytes.length + size;
			this.haveCacheBytes = false;

		}

		if (size < Packet.IP4_HEADER_SIZE) {
			byte[] data = new byte[size];
			System.arraycopy(bytes, 0, data, 0, size);
			this.cacheBytes = data;
			this.haveCacheBytes = true;
			// System.out.println("bad packet size, CacheBytes");
			return;
		}

		IPHeader IpHeader = new IPHeader(bytes, 0);
		int totalLength = IpHeader.getTotalLength();

		if (size > totalLength) {
			processIPPacket(bytes, 0, totalLength);
			int nextDataSize = size - totalLength;
			byte[] data = new byte[nextDataSize];
			System.arraycopy(bytes, totalLength, data, 0, nextDataSize);
			processRecvPacket(data, nextDataSize);
		} else if (size == totalLength) {
			processIPPacket(bytes, 0, size);
		} else if (size < totalLength) {
			byte[] data = new byte[size];
			System.arraycopy(bytes, 0, data, 0, size);
			this.cacheBytes = data;
			this.haveCacheBytes = true;
		}
	}
	
	public void processIPPacket(byte[] bytes, int offset, int size) throws UnknownHostException {
		IPHeader header = new IPHeader(bytes, 0);
		byte protocol = header.getProtocol();
		if (protocol == IPHeader.TCP) {
			tcpTunnel.processPacket(bytes, offset, size);
		} else if (protocol == IPHeader.UDP) {
			udpTunnel.processPacket(bytes, offset, size);
		}else {
			System.out.println("未知包");
			close();
		}
	}
	
	@Override
	public void run() {
		try {
			int readSize = 0;
			byte[] readBytes = new byte[Packet.IP4_HEADER_SIZE];
			while (readSize < Packet.IP4_HEADER_SIZE) {
				int size = is.read(readBytes, readSize, Packet.IP4_HEADER_SIZE - readSize);
				if(size == -1) return;
				readSize += size;
			}
			IPHeader header = new IPHeader(readBytes, 0);
			if (header.getSourceIP() != 12345678 && header.getDestinationIP() != 87654321) {
				return;
			}

			int size = 0;
			while (size != -1 && !isClose()) {
				byte[] bytes = new byte[Config.MUTE];
				size = is.read(bytes);
				if (size > 0) {
					processRecvPacket(bytes, size);
				}
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			// e.printStackTrace();
		} finally {
			close();
		}
	}

}
