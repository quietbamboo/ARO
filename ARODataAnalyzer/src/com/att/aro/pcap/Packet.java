/*
 Copyright [2012] [AT&T]
 
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
 
       http://www.apache.org/licenses/LICENSE-2.0
 
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package com.att.aro.pcap;

import java.io.Serializable;
import java.nio.ByteBuffer;

/**
 * An adapter class for packet data.
 */
public class Packet implements Serializable {
	private static final long serialVersionUID = 1L;

	private static final short IP = 0x0800;

	private static final int DLT_EN10MB = 1;
	private static final int DLT_RAW = 12;
	private static final int DLT_LINUX_SLL = 113;

	private static final int NETMON_ETHERNET = 1;
	private static final int NETMON_WIFI = 6;
	private static final int NETMON_WIRELESSWAN = 8;
	private static final int NETMON_RAW = 9;

	/**
	 * Creates a new instance of the Packet class.
	 */
	public static Packet createPacketFromPcap(int datalink, long seconds, long microSeconds, int len,
			byte[] data) {

		// Determine network protocol
		short network = 0;
		int hdrLen = 0;
		ByteBuffer bytes = ByteBuffer.wrap(data);
		try {
			switch (datalink) {
			case DLT_RAW: // Raw IP
				network = IP;
				break;
			case DLT_EN10MB: // Ethernet (WiFi)
				network = bytes.getShort(12);
				hdrLen = 14;
				break;
			case DLT_LINUX_SLL: // Linux cooked capture (Android)
				network = bytes.getShort(14);
				hdrLen = 16;
				break;
			}
		} catch (IndexOutOfBoundsException e) {
			// Truncated packet
		}
		
		return createPacket(network, seconds, microSeconds, len, hdrLen, data);
	}

	/**
	 * Creates a new instance of the Packet class.
	 */
	public static Packet createPacketFromNetmon(int datalink, long seconds, long microSeconds, int len,
			byte[] data) {

		// Check for PCAP datalink
		if (datalink >= 0xe000 && datalink <= 0xefff) {
			return createPacketFromPcap(datalink - 0xe000, seconds, microSeconds, len, data);
		}
		
		// Determine network protocol
		short network = 0;
		int hdrLen = 0;
		ByteBuffer bytes = ByteBuffer.wrap(data);
		try {
			switch (datalink) {
			case NETMON_RAW: // Raw IP
			case NETMON_WIRELESSWAN:
				network = IP;
				break;
			case NETMON_ETHERNET: // Ethernet (WiFi)
				network = bytes.getShort(12);
				hdrLen = 14;
				break;
			case NETMON_WIFI:
				
				// Get the NetMon 802.11 capture header length
				hdrLen = bytes.get(1);
				
				// Read the IEEE 802.11 frame control flags
				short control = bytes.get(hdrLen);
				
				// Check for data frame type
				if ((control & 0x000c) == 0x0008) {
					
					// Check data frame sub-type
					if ((control & 0x0080) == 0) {
						// Data
						hdrLen += 32;
					} else {
						// Data - QoS sub-type
						hdrLen += 34;
					}
					network = bytes.getShort(hdrLen - 2);
				}
			}
		} catch (IndexOutOfBoundsException e) {
			// Truncated packet
		}
		
		return createPacket(network, seconds, microSeconds, len, hdrLen, data);
	}

	/**
	 * Creates a new instance of the Packet class.
	 */
	public static Packet createPacket(short network, long seconds, long microSeconds, int len, int datalinkHdrLen,
			byte[] data) {

		// Minimum IP header length is 20 bytes
		ByteBuffer bytes = ByteBuffer.wrap(data);
		if (network == IP && data.length >= datalinkHdrLen + 20) {

			byte iphlen = (byte) ((bytes.get(datalinkHdrLen) & 0x0f) << 2);
			if (data.length < datalinkHdrLen + iphlen) {

				// Truncated packet
				return new Packet(seconds, microSeconds, len, datalinkHdrLen, data);
			}

			// Determine IP protocol
			byte protocol = bytes.get(datalinkHdrLen + 9);
			switch (protocol) {
			case 6: // TCP
				if (data.length >= datalinkHdrLen + iphlen + 20) {
					return new TCPPacket(seconds, microSeconds, len, datalinkHdrLen, data);
				} else {
					return new Packet(seconds, microSeconds, len, datalinkHdrLen, data);
				}
			case 17: // UDP
				if (data.length >= datalinkHdrLen + iphlen + 6) {
					return new UDPPacket(seconds, microSeconds, len, datalinkHdrLen, data);
				} else {
					return new Packet(seconds, microSeconds, len, datalinkHdrLen, data);
				}
			default:
				return new IPPacket(seconds, microSeconds, len, datalinkHdrLen, data);
			}
		} else {
			return new Packet(seconds, microSeconds, len, datalinkHdrLen, data);
		}

	}

	private byte[] data;
	private long seconds;
	private long microSeconds;
	private int len;
	private int dataOffset;

	/**
	 * Constructor
	 */
	protected Packet(long seconds, long microSeconds, int len, int datalinkHdrLen, byte[] data) {
		this.dataOffset = datalinkHdrLen;
		this.seconds = seconds;
		this.microSeconds = microSeconds;
		this.len = len;
		this.data = data;
	}

	/**
	 * Gets the data portion of the packet.
	 * 
	 * @return The packet data.
	 */
	public byte[] getData() {
		return data;
	}

	/**
	 * Gets the number of seconds.
	 * 
	 * @return A long that is tThe number of seconds.
	 */
	public long getSeconds() {
		return this.seconds;
	}

	/**
	 * Gets the number of microseconds.
	 * 
	 * @return A long that is the number of microseconds.
	 */
	public long getMicroSeconds() {
		return this.microSeconds;
	}

	/**
	 * Calculates and returns the timestamp value.
	 * 
	 * @return A double that is the timestamp value.
	 */
	public double getTimeStamp() {
		return ((double) seconds) + (((double) microSeconds) / 1000000.0);
	}

	/**
	 * Gets the length of the packet (in bytes) including both the header and
	 * the data.
	 * 
	 * @return An int that is the length of the packet (in bytes).
	 */
	public int getLen() {
		return len;
	}

	/**
	 * Returns the length of the data portion of the packet. Subclasses should
	 * override this method to identify their specific payload.
	 * 
	 * @return The payload length.
	 */
	public int getPayloadLen() {

		// Use method here rather than member for data offset in case
		// overridden by subclass
		return len - getDataOffset();
	}

	/**
	 * This method returns the offset into the data array where the payload of
	 * the packet starts. Subclasses should override this to give proper data
	 * offset excluding enclosed headers.
	 * 
	 * @return The offset within the data array of the packet data excluding the
	 *         header info
	 */
	public int getDataOffset() {
		return dataOffset;
	}

	/**
	 * Returns the size of the datalink header on the packet.
	 * 
	 * @return The size of the datalink header for the packet.
	 */
	public final int getDatalinkHeaderSize() {
		return dataOffset;
	}
}
