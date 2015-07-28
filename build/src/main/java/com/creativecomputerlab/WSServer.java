package com.creativecomputerlab;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.codeminders.hidapi.HIDDeviceInfo; 
import com.codeminders.hidapi.HIDManager; 
import com.codeminders.hidapi.*;
import java.util.*;

public class WSServer extends WebSocketServer {

  private Set<WebSocket> conns;  //List of connected clients
  private HIDDevice device = null;
  public boolean writing = false;
  private Object writeLock = null;
  
  /**
   * Creates a new WebSocketServer listening on a given port with access to a given HID device initialized by the caller.
   */
  public WSServer(int PORT, HIDDevice dev, Object lock) {
    super(new InetSocketAddress(PORT));
	device = dev;
	writeLock = lock;
	System.out.println("Starting Websocket listener on port " + PORT);
		 if (device!=null) {
		 try {
			System.out.println("Device Manufacturer: " + device.getManufacturerString() + ";  Product:   " + device.getProductString());
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
    conns = new HashSet<>();
  }
  
  /** 
   * Handle a  new connection. 
   */
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    conns.add(conn);
    System.out.println("New connection from " + conn.getRemoteSocketAddress().getAddress().getHostAddress());
  }

  /** 
   * Handle connection closing.
   */
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    conns.remove(conn);
    System.out.println("Closed connection to " + conn.getRemoteSocketAddress().getAddress().getHostAddress());
  }

  /** 
   * Handle received message.
   */
  public void onMessage(WebSocket conn, String message) {
	if (!writing) { //Drop write requests if the occur too rapidly
		writeDevice(message);
	} else 
		System.out.println ("Ignored write request");
 }
   
   /** 
   * Handle message sending.
   */
  public void sendMessage(String message) {
      for (WebSocket sock : conns) {
        sock.send(message);
      }
  }
  
  /** 
   * Set the device handle.
   */
  public void setDevice(HIDDevice dev) {
	device = dev; 
	 if (device!=null) {
		 try {
			System.out.println("Added Device.   Manufacturer: " + device.getManufacturerString() + ";  Product:   " + device.getProductString());
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
  } 
  
  public void setLock (Object lock) {
	writeLock = lock; 
  }
  
   /** 
   * Error handler.
   */
  public void onError(WebSocket conn, Exception ex) {
    conns.remove(conn);
    System.out.println("ERROR from " + conn.getRemoteSocketAddress().getAddress().getHostAddress());
  }

  /**
   * Use for standalone testing
   */
  public static void main(String[] args) {
    WSServer server = new WSServer(8887, null, null);
    server.start();
  }
  
  private synchronized void writeDevice(String message) {
	synchronized (writeLock) {
	writing = true;
      //System.out.println("Received: " + message);
	String [] msg = message.split(":");
	/**
	 * This is where incoming read or write requests from the client are processed to set output channels either high or low.
	 * For now, the simple protocol message format is:
			ch:n:H    -- This message indicates that the client wants to set channel n to high.
			ch:n:L    -- This message indicates that the client wants to set channel n to low.
	*/	
	
    if (device!=null) {
      byte buf[]=new byte[16];
      buf[0]=(byte)3;
      buf[1]=(byte)73;   //'I' ASCII=73
 	  buf[2]=(byte)msg[2].charAt(0);   //'H' = 72, 'L' = 76
      buf[3]=(byte)msg[1].charAt(0);   // Channel number in ASCII

      //System.out.println("Sending buf: {" + buf[0] + ", " + buf[1] + ", " + buf[2] + ", " + buf[3] + "}");  
 	  
	  try {
		  int bytesRead =  -1;
		  bytesRead = device.write(buf);
		  //System.out.println ("Wrote " + bytesRead + " bytes to device OK");
	  }
	  catch (Exception ex) {
		System.out.println ("Wrote to device ERROR");
		ex.printStackTrace();
	  }	
	  finally {
	  		writing = false;
	  }
	}

    }
  
}
}