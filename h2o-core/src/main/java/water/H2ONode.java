package water;

import water.nbhm.NonBlockingHashMap;
import water.nbhm.NonBlockingHashMapLong;
import water.network.SocketChannelFactory;
import water.util.ArrayUtils;
import water.util.Log;
import water.util.MathUtils;
import water.util.UnsafeUtils;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A <code>Node</code> in an <code>H2O</code> Cloud.
 * Basically a worker-bee with CPUs, Memory and Disk.
 * One of this is the self-Node, but the rest are remote Nodes.
 *
 * @author <a href="mailto:cliffc@h2o.ai"></a>
 * @version 1.0
 */

public final class H2ONode extends Iced<H2ONode> implements Comparable {

  private static final int SEND_THREAD_STOP_DELAY_COEF = 10;

  transient private SocketChannelFactory _socketFactory;
  transient private H2OSecurityManager _security;

  transient short _unique_idx; // Dense integer index, skipping 0.  NOT cloud-wide unique.
  transient boolean _announcedLostContact;  // True if heartbeat published a no-contact msg
  transient public long _last_heard_from; // Time in msec since we last heard from this Node
  transient public volatile HeartBeat _heartbeat;  // My health info.  Changes 1/sec.
  transient public int _tcp_readers;               // Count of started TCP reader threads

  public transient short _timestamp;
  public transient boolean _client;

  public boolean _removed_from_cloud;

  void stopSendThread() {
    stopSendThread(0L);
  }

  private void stopSendThread(long gracePeriod) {
    if (_sendThread == null) { // send thread can only be null if H2ONode was serialized
      throw new IllegalStateException("Cannot invoke stop on a deserialized H2ONode.");
    }
    _sendThread.stopSending(gracePeriod);
    _removed_from_cloud = true;
  }

  private void startSendThread() {
    _sendThread = new UDP_TCP_SendThread(); // Launch the UDP send thread
    _sendThread.start();
    _removed_from_cloud = false;
  }

  // A JVM is uniquely named by machine IP address and port#
  public final H2Okey _key;

  /** Identification of the node via IP and PORT.
   *
   */
  static final class H2Okey extends InetSocketAddress implements Comparable {
    // Numeric representation of IP
    // For IPv6 the both fields are valid and describes full IPv6 address, for IPv4 only low 32 bits of _ipLow are valid
    // But still need a flag to distinguish between IPv4 and IPv6
    final long _ipHigh, _ipLow; // IPv4: A.B.C.D ~ DCBA
    H2Okey(InetAddress inet, int port) {
      super(inet, port);
      byte[] b = inet.getAddress(); // 4bytes or 16bytes
      if (b.length == 4) {
        assert !H2O.IS_IPV6 : "IPv4 stack specified but IPv6 address passed! " + inet;
        _ipHigh = 0;
        _ipLow = ArrayUtils.encodeAsInt(b) & 0XFFFFFFFFL;
      } else {
        assert H2O.IS_IPV6 : "IPv6 stack specified but IPv4 address passed! " + inet;
        _ipHigh = ArrayUtils.encodeAsLong(b, 8, 8);
        _ipLow = ArrayUtils.encodeAsLong(b, 0, 8);
      }
    }
    public int htm_port() { return getPort()-H2O.ARGS.port_offset; }
    public int udp_port() { return getPort()  ; }
    @Override public String toString() { return getAddress()+":"+htm_port(); }
    public String getIpPortString() {
      return getAddress().getHostAddress() + ":" + htm_port();
    }
    AutoBuffer write( AutoBuffer ab ) {
      return (!H2O.IS_IPV6
              ? ab.put4((int) _ipLow)
              : ab.put8(_ipLow).put8(_ipHigh)).put2((char) udp_port());
    }
    static H2Okey read( AutoBuffer ab ) {
      try {
        InetAddress inet = InetAddress.getByAddress(ab.getA1(SIZE_OF_IP));
        int port = ab.get2();
        return new H2Okey(inet, port);
      } catch( UnknownHostException e ) { throw Log.throwErr(e); }
    }
    // Canonical ordering based on inet & port
    @Override public int compareTo(Object x) {
      if( x == null ) return -1;   // Always before null
      if( x == this ) return 0;
      H2Okey key = (H2Okey)x;
      // Must be unsigned long-arithmetic, or overflow will make a broken sort
      int res = MathUtils.compareUnsigned(_ipHigh, _ipLow, key._ipHigh, key._ipLow);
      return res != 0 ? res : udp_port() - key.udp_port();
    }

    static int SIZE_OF_IP = H2O.IS_IPV6 ? 16 : 4;
    /** Size of serialized H2OKey */
    static int SIZE = SIZE_OF_IP /* ip */ + 2 /* port */;
  }

  public String getIpPortString() {
    return _key.getIpPortString();
  }

  public final int ip4() { return (int) _key._ipLow; }

  // These are INTERN'd upon construction, and are uniquely numbered within the
  // same run of a JVM.  If a remote Node goes down, then back up... it will
  // come back with the SAME IP address, and the same unique_idx and history
  // relative to *this* Node.  They can be compared with pointer-equality.  The
  // unique idx is used to know which remote Nodes have cached which Keys, even
  // if the Home#/Replica# change for a Key due to an unrelated change in Cloud
  // membership.  The unique_idx is *per Node*; not all Nodes agree on the same
  // indexes.
  private H2ONode( H2Okey key, short unique_idx, short timestamp) {
    _key = key;
    _unique_idx = unique_idx;
    _last_heard_from = System.currentTimeMillis();
    _heartbeat = new HeartBeat();
    _timestamp = timestamp;
    _client = H2O.decodeIsClient(timestamp);

    _security = H2OSecurityManager.instance();
    _socketFactory = SocketChannelFactory.instance(_security);
    startSendThread(); // never allow an H2ONode that cannot be used right away to send messages
  }

  public boolean isHealthy() { return isHealthy(System.currentTimeMillis()); }
  public boolean isHealthy(long now) {
    return (now - _last_heard_from) < HeartBeatThread.TIMEOUT;
  }

  // ---------------
  // A dense integer index for every unique IP ever seen, since the JVM booted.
  // Used to track "known replicas" per-key across Cloud change-ups.  Just use
  // an array-of-H2ONodes
  static private final NonBlockingHashMap<H2Okey,H2ONode> INTERN = new NonBlockingHashMap<>();
  static private final AtomicInteger UNIQUE = new AtomicInteger(1);
  static H2ONode IDX[] = new H2ONode[1];

  static H2ONode[] getClients(){
    ArrayList<H2ONode> clients = new ArrayList<>(INTERN.size());
    for( Map.Entry<H2Okey, H2ONode> entry : INTERN.entrySet()){
      if (entry.getValue()._client) {
        clients.add(entry.getValue());
      }
    }
    return clients.toArray(new H2ONode[0]);
  }

  static H2ONode getClientByIPPort(String ipPort){
    for( Map.Entry<H2Okey, H2ONode> entry : INTERN.entrySet()){
      if (entry.getValue()._client && entry.getValue().getIpPortString().equals(ipPort)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private synchronized void refreshClient(short timestamp) {
    assert timestamp != 0 && H2O.decodeIsClient(timestamp);

    boolean wasKnownClient = _timestamp != 0;

    if (wasKnownClient) {
      Log.info("Client reconnected with a new timestamp=" + _timestamp + ", old client: " + toDebugString());
    } else {
      Log.info("Node " + _key + " identified as client node, timestamp=" + _timestamp);
    }
    _client = true;
    _timestamp = timestamp;
    _last_heard_from = System.currentTimeMillis();

    UDP_TCP_SendThread oldSendThread = _sendThread;
    startSendThread();
    if (wasKnownClient) {
      oldSendThread.stopSending(0L); // we can just drop the messages - we are talking to a brand new client
    } else {
      oldSendThread.stopSending(gracePeriod()); // we want to avoid losing messages
    }
  }

  private long gracePeriod() {
    return SEND_THREAD_STOP_DELAY_COEF * H2O.ARGS.clientDisconnectTimeout;
  }
  
  boolean removeClient() {
    assert _timestamp == 0 || H2O.decodeIsClient(_timestamp);
    boolean removed = INTERN.remove(_key, this);
    if (removed) {
      Log.info("Removing client: " + toDebugString());
    } else {
      Log.debug("Attempted to remove a client which was already superseded by another client: " + toDebugString());
    }
    // Stop the sending thread after a short grace period:
    //   We want to give the thread a chance to finish sending current messages (if the client is not really inactive 
    //   and we were just not getting the heartbeat)
    // The grace period is chosen to be a multiple of the client disconnect timeout.
    long gracePeriod = gracePeriod();
    Log.warn("Will keep trying to deliver unsent messages to " + _key + " for the next " + (gracePeriod / 1000) + "s. " +
            "You might see error messages reported by thread " + _sendThread.getName() + " if the client is not reachable.");
    stopSendThread(gracePeriod);
    return removed;
  }

  // Create and/or re-use an H2ONode.  Each gets a unique dense index, and is
  // *interned*: there is only one per InetAddress.
  static private H2ONode intern(H2Okey key, short timestamp) {
    boolean isClient = H2O.decodeIsClient(timestamp);
    H2ONode h2o = INTERN.get(key);
    if (h2o != null) {
      if (isClient && timestamp != h2o._timestamp) {
        h2o.refreshClient(timestamp);
      }
      return h2o;
    } else {
      if (isClient) {
        Log.info("New client connected, timestamp=" + timestamp);
      }
    }
    final int idx = UNIQUE.getAndIncrement();
    assert idx < Short.MAX_VALUE;
    h2o = new H2ONode(key, (short) idx, timestamp);
    H2ONode old = INTERN.putIfAbsent(key, h2o);
    if (old != null) {
      if (isClient && timestamp != old._timestamp) {
        old.refreshClient(timestamp);
      }
      h2o.stopSendThread(); // expensive but shouldn't happen often
      return old;
    }
    synchronized (H2O.class) {
      while (idx >= IDX.length) {
        IDX = Arrays.copyOf(IDX, IDX.length << 1);
      }
      IDX[idx] = h2o;
    }
    return h2o;
  }

  public static H2ONode intern(InetAddress ip, int port, short timestamp) { return intern(new H2Okey(ip, port), timestamp); }

  public static H2ONode intern(InetAddress ip, int port) { return intern(ip, port, (short) 0); }

  public static H2ONode intern(byte[] bs, int off) {
    byte[] b = new byte[H2Okey.SIZE_OF_IP]; // the size depends on version of selected IP stack
    int port;
    // The static constant should be optimized
    if (!H2O.IS_IPV6) { // IPv4
      UnsafeUtils.set4(b, 0, UnsafeUtils.get4(bs, off));
    } else { // IPv6
      UnsafeUtils.set8(b, 0, UnsafeUtils.get8(bs, off));
      UnsafeUtils.set8(b, 8, UnsafeUtils.get8(bs, off + 8));
    }
    port = UnsafeUtils.get2(bs,off + H2Okey.SIZE_OF_IP) & 0xFFFF;
    try { return intern(InetAddress.getByAddress(b),port); }
    catch( UnknownHostException e ) { throw Log.throwErr(e); }
  }

  // Get a nice Node Name for this Node in the Cloud.  Basically it's the
  // InetAddress we use to communicate to this Node.
  public static H2ONode self(InetAddress local) {
    assert H2O.H2O_PORT != 0;
    try {
      // Figure out which interface matches our IP address
      List<NetworkInterface> matchingIfs = new ArrayList<>();
      Enumeration<NetworkInterface> netIfs = NetworkInterface.getNetworkInterfaces();
      while( netIfs.hasMoreElements() ) {
        NetworkInterface netIf = netIfs.nextElement();
        Enumeration<InetAddress> addrs = netIf.getInetAddresses();
        while( addrs.hasMoreElements() ) {
          InetAddress addr = addrs.nextElement();
          if( addr.equals(local) ) {
            matchingIfs.add(netIf);
            break;
          }
        }
      }
      switch( matchingIfs.size() ) {
      case 0: H2O.CLOUD_MULTICAST_IF = null; break;
      case 1: H2O.CLOUD_MULTICAST_IF = matchingIfs.get(0); break;
      default:
        String msg = "Found multiple network interfaces for ip address " + local;
        for( NetworkInterface ni : matchingIfs ) {
          msg +="\n\t" + ni;
        }
        msg +="\nUsing " + matchingIfs.get(0) + " for UDP broadcast";
        Log.warn(msg);
        H2O.CLOUD_MULTICAST_IF = matchingIfs.get(0);
      }
    } catch( SocketException e ) {
      throw Log.throwErr(e);
    }

    // Selected multicast interface must support multicast, and be up and running!
    try {
      if( H2O.CLOUD_MULTICAST_IF != null && !H2O.CLOUD_MULTICAST_IF.supportsMulticast() ) {
        Log.info("Selected H2O.CLOUD_MULTICAST_IF: "+H2O.CLOUD_MULTICAST_IF+ " doesn't support multicast");
//        H2O.CLOUD_MULTICAST_IF = null;
      }
      if( H2O.CLOUD_MULTICAST_IF != null && !H2O.CLOUD_MULTICAST_IF.isUp() ) {
        throw new RuntimeException("Selected H2O.CLOUD_MULTICAST_IF: "+H2O.CLOUD_MULTICAST_IF+ " is not up and running");
      }
    } catch( SocketException e ) {
      throw Log.throwErr(e);
    }

    return intern(new H2Okey(local, H2O.H2O_PORT), H2O.calculateNodeTimestamp());
  }

  // Happy printable string
  @Override public String toString() { return _key.toString (); }

  public String toDebugString() {
    String base = _key.toString();
    if (! _client) {
      return base;
    }
    StringBuilder sb = new StringBuilder(base);
    sb.append("(");
    sb.append("timestamp=").append(_timestamp);
    if (_heartbeat != null) {
      sb.append(", ").append("watchdog=").append(_heartbeat._watchdog_client);
      sb.append(", ").append("cloud_name_hash=").append(_heartbeat._cloud_name_hash);
    }
    sb.append(")");
    return sb.toString();
  }

  @Override public int hashCode() { return _key.hashCode(); }
  @Override public boolean equals(Object o) { return _key.equals   (((H2ONode)o)._key); }
  @Override public int compareTo( Object o) { return _key.compareTo(((H2ONode)o)._key); }

  // index of this node in the current cloud... can change at the next cloud.
  public int index() { return H2O.CLOUD.nidx(this); }

  // ---------------
  // A queue of available TCP sockets
  // re-usable TCP socket opened to this node, or null.
  // This is essentially a BlockingQueue/Stack that allows null.
  private transient ByteChannel _socks[] = new ByteChannel[2];
  private transient int _socksAvail=_socks.length;
  // Count of concurrent TCP requests both incoming and outgoing
  static final AtomicInteger TCPS = new AtomicInteger(0);

  ByteChannel getTCPSocket() throws IOException {
    // Under lock, claim an existing open socket if possible
    synchronized(this) {
      // Limit myself to the number of open sockets from node-to-node
      while( _socksAvail == 0 )
        try { wait(1000); } catch( InterruptedException ignored ) { }
      // Claim an open socket
      ByteChannel sock = _socks[--_socksAvail];
      if( sock != null ) {
        if( sock.isOpen() ) return sock; // Return existing socket!
        // Else it's an already-closed socket, lower open TCP count
        assert TCPS.get() > 0;
        TCPS.decrementAndGet();
      }
    }
    // Must make a fresh socket
    SocketChannel sock2 = SocketChannel.open();
    sock2.socket().setReuseAddress(true);
    sock2.socket().setSendBufferSize(AutoBuffer.BBP_BIG._size);
    boolean res = sock2.connect( _key );
    assert res && !sock2.isConnectionPending() && sock2.isBlocking() && sock2.isConnected() && sock2.isOpen();
    ByteBuffer bb = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder());
    bb.put((byte)2);
    bb.putShort(H2O.SELF._timestamp);
    bb.putChar((char)H2O.H2O_PORT);
    bb.put((byte)0xef);
    bb.flip();
    ByteChannel wrappedSocket = _socketFactory.clientChannel(sock2, _key.getHostName(), _key.getPort());
    while(bb.hasRemaining()) {
      wrappedSocket.write(bb);
    }
    TCPS.incrementAndGet();     // Cluster-wide counting
    return wrappedSocket;
  }
  synchronized void freeTCPSocket( ByteChannel sock ) {
    assert 0 <= _socksAvail && _socksAvail < _socks.length;
    assert TCPS.get() > 0;
    if( sock != null && !sock.isOpen() ) sock = null;
    _socks[_socksAvail++] = sock;
    if( sock == null ) TCPS.decrementAndGet();
    notify();
  }

  // ---------------
  // Send UDP via batched TCP.  Note: has to happen out-of-band with the
  // standard AutoBuffer writing, which can hit the case of needing a TypeId
  // mapping mid-serialization.  Thus this path uses another TCP channel that
  // is specifically not any of the above channels.  This channel is limited to
  // messages which are presented in their entirety (not streamed) thus never
  // need another (nested) TCP channel.
  private transient UDP_TCP_SendThread _sendThread = null; // can only be null if deserialized, otherwise not-null in normal operation
  public final void sendMessage(ByteBuffer bb, byte msg_priority) {
    if (_client) { // clients get special handling because they can be flaky - disconnect and reconnect 
      sendClientMessage(bb, msg_priority);
    } else
      _sendThread.sendMessage(bb, msg_priority);
  }

  // We need to handle cases when the sender might be holding an old instance of H2ONode that is not interned anymore
  // (was just removed from the cluster). 
  private void sendClientMessage(ByteBuffer bb, byte msg_priority) {
    H2ONode interned = INTERN.get(_key);
    if (interned == this) { // the usual case
      _sendThread.sendMessage(bb, msg_priority);
    } else if (interned != null) { // client reconnected before the sender realized it - use the interned sending thread instead
      Log.debug("Delegating to an active instance of H2ONode to send a message, node: " + _key);
      interned._sendThread.sendMessage(bb, msg_priority);
    } else { // interned == null; client appears disconnected
      Log.warn("Trying to send a message to a client that appears to be disconnected, node: " + _key);
      _sendThread.sendMessage(bb, msg_priority); // this might drop the message
    }
  }

  /**
   * Returns a new connection of type {@code tcpType}, the type can be either
   *   TCPReceiverThread.TCP_SMALL, TCPReceiverThread.TCP_BIG or
   *   TCPReceiverThread.TCP_EXTERNAL.
   *
   * In case of TCPReceiverThread.TCP_EXTERNAL, we need to keep in mind that this method is executed in environment
   * where H2O is not running, but it is just on the classpath so users can establish connection with the external H2O
   * cluster.
   *
   * If socket channel factory is set, the communication will considered to be secured - this depends on the
   * configuration of the {@link SocketChannelFactory}. In case of the factory is null, the communication won't be secured.
   * @return new socket channel
   */
  public static ByteChannel openChan(byte tcpType, SocketChannelFactory socketFactory, InetAddress originAddr, int originPort, short nodeTimeStamp) throws IOException {
    // This method can't internally use static fields which depends on initialized H2O cluster in case of
    //communication to the external H2O cluster
    // Must make a fresh socket
    SocketChannel sock = SocketChannel.open();
    sock.socket().setReuseAddress(true);
    sock.socket().setSendBufferSize(AutoBuffer.BBP_BIG._size);
    InetSocketAddress isa = new InetSocketAddress(originAddr, originPort);
    boolean res = sock.connect(isa); // Can toss IOEx, esp if other node is still booting up
    assert res : "Should be already connected, but connection is in non-blocking mode and the connection operation is in progress!";
    sock.configureBlocking(true);
    assert !sock.isConnectionPending() && sock.isBlocking() && sock.isConnected() && sock.isOpen();
    sock.socket().setTcpNoDelay(true);
    ByteBuffer bb = ByteBuffer.allocate(6).order(ByteOrder.nativeOrder());
    // In Case of tcpType == TCPReceiverThread.TCP_EXTERNAL, H2O.H2O_PORT is 0 as it is undefined, because
    // H2O cluster is not running in this case. However,
    // it is fine as the receiver of the external backend does not use this value.
    bb.put(tcpType).putShort(nodeTimeStamp).putChar((char)H2O.H2O_PORT).put((byte) 0xef).flip();

    ByteChannel wrappedSocket = socketFactory.clientChannel(sock, isa.getHostName(), isa.getPort());

    while (bb.hasRemaining()) {  // Write out magic startup sequence
      wrappedSocket.write(bb);
    }
    return wrappedSocket;
  }

  public static ByteChannel openChan(byte tcpType, SocketChannelFactory socketFactory, String originAddr, int originPort, short nodeTimeStamp) throws IOException {
    return openChan(tcpType, socketFactory, InetAddress.getByName(originAddr), originPort, nodeTimeStamp);
  }

  // Private thread serving (actually ships the bytes over) small msg Q.
  // Buffers the small messages together and sends the bytes over via TCP channel.
  class UDP_TCP_SendThread extends Thread {

    private final ByteBuffer POISON_PILL = ByteBuffer.allocate(0);

    private volatile boolean _stopRequested;
    private ByteChannel _chan;  // Lazily made on demand; closed & reopened on error
    private final ByteBuffer _bb; // Reusable output large buffer

    public UDP_TCP_SendThread(){
      super("UDP-TCP-SEND-" + H2ONode.this);
      _bb = AutoBuffer.BBP_BIG.make();
    }

    /** Send small message to this node.  Passes the message on to a private msg
     *  q, prioritized by the message priority.  MSG queue is served by sender
     *  thread, message are continuously extracted, buffered together and sent
     *  over TCP channel.
     *  @param bb Message to send
     *  @param msg_priority priority (e.g. NACK and ACKACK beat most other priorities
     */
    private void sendMessage(ByteBuffer bb, byte msg_priority) {
      assert bb.position()==0 && bb.limit() > 0;

      if (_stopRequested) {
        Log.err("Message for node " + _key + " will be dropped! This sending thread is not active anymore.");
        return;
      }

      // Secret back-channel priority: the position field (capped at bb.limit);
      // this is to avoid making Yet Another Object per send.

      // Priority can exceed position.  "interesting" priorities are everything
      // above H2O.MIN_HI_PRIORITY and things just above 0; priorities in the
      // middl'n range from 10 to MIN_HI are really rare.  Need to compress
      // priorities a little for this hack to work.
      if( msg_priority >= H2O.MIN_HI_PRIORITY ) msg_priority = (byte)((msg_priority-H2O.MIN_HI_PRIORITY)+10);
      else if( msg_priority >= 10 ) msg_priority = 10;
      if( msg_priority > bb.limit() ) msg_priority = (byte)bb.limit();
      bb.position(msg_priority);

      _msgQ.put(bb);
    }

    void stopSending(long delay) {
      if (delay == 0) {
        requestStop();
      } else {
        H2O.submitTask(new StopSendThreadTask(this, delay));
      }
    }
    
    private void requestStop() {
      _stopRequested = true;
      // note: priority of the POISON_PILL doesn't matter - it is just something to unblock the thread waiting for a message
      _msgQ.put(POISON_PILL);
    }

    private final PriorityBlockingQueue<ByteBuffer> _msgQ
      = new PriorityBlockingQueue<>(11,new Comparator<ByteBuffer>() {
          // Secret back-channel priority: the position field (capped at bb.limit)
          @Override public int compare( ByteBuffer bb1, ByteBuffer bb2 ) { return bb1.position() - bb2.position(); }
        });

    @Override public void run(){
      try {
        while (!_stopRequested) {            // Forever loop
          try {
            ByteBuffer bb = _msgQ.take(); // take never returns null but blocks instead
            if (bb == POISON_PILL) {
              assert _stopRequested;
              break; // stop sending
            }
            while( bb != null ) {         // while have an BB to process
              assert !bb.isDirect() : "Direct BBs already got recycled";
              assert bb.limit()+1+2 <= _bb.capacity() : "Small message larger than the output buffer";
              if( _bb.remaining() < bb.limit()+1+2 )
                sendBuffer();     // Send full batch; reset _bb so taken bb fits
              _bb.putChar((char)bb.limit());
              _bb.put(bb.array(),0,bb.limit()); // Jam this BB into the existing batch BB, all in one go (it all fits)
              _bb.put((byte)0xef);// Sentinel byte
              bb = _msgQ.poll();  // Go get more, same batch
            }
            sendBuffer();         // Send final trailing BBs
          } catch (IllegalMonitorStateException imse) { /* ignore */
          } catch (InterruptedException e) { /*ignore*/ }
        }
      } catch(Throwable t) { throw Log.throwErr(t); }
      if(_chan != null) {
        try {_chan.close();} catch (IOException e) {}
        _chan = null;
      }
    }

    void sendBuffer(){
      int retries = 0;
      _bb.flip();                 // limit set to old position; position set to 0
      while( !_stopRequested && _bb.hasRemaining()) {
        try {
          ByteChannel chan = _chan == null ? (_chan=openChan()) : _chan;
          chan.write(_bb);
        } catch(IOException ioe) {
          _bb.rewind();           // Position to zero; limit unchanged; retry the operation
          // Log if not shutting down, and not middle-of-cloud-formation where
          // other node is still booting up (expected common failure), or *never*
          // comes up - such as when not all nodes mentioned in a flatfile will be
          // booted.  Basically the ERRR log will show endless repeat attempts to
          // connect to the missing node
          if( !_stopRequested && !H2O.getShutdownRequested() && (Paxos._cloudLocked || retries++ > 300) ) {
            Log.err("Got IO error when sending batch UDP bytes: ",ioe);
            retries = 150;      // Throttle the pace of error msgs
          }
          if( _chan != null )
            try { _chan.close(); } catch (Throwable t) {/*ignored*/}
          _chan = null;
          retries++;
          final int sleep = Math.min(5000,retries << 1);
          try {Thread.sleep(sleep);} catch (InterruptedException e) {/*ignored*/}
        }
      }
      _bb.clear();            // Position set to 0; limit to capacity
    }

    // Open channel on first write attempt
    private ByteChannel openChan() throws IOException {
      return H2ONode.openChan(TCPReceiverThread.TCP_SMALL, _socketFactory, _key.getAddress(), _key.getPort(), H2O.SELF._timestamp);
    }
  }

  private static class StopSendThreadTask extends H2O.H2OCountedCompleter<StopSendThreadTask> {
    private transient UDP_TCP_SendThread _sendThread;
    private long _delay;
    private StopSendThreadTask(UDP_TCP_SendThread sendThread, long delay) {
      _sendThread = sendThread;
      _delay = delay;
    }
    @Override
    public void compute2() {
      assert _sendThread != null;
      try {
        Thread.sleep(_delay);
      } catch (InterruptedException e) {
        Log.trace(e);
      }
      _sendThread.requestStop();
      tryComplete();
    }
  }

  // ---------------
  // The *outgoing* client-side calls; pending tasks this Node wants answered.
  private final NonBlockingHashMapLong<RPC> _tasks = new NonBlockingHashMapLong<>();
  void taskPut(int tnum, RPC rpc ) {
    _tasks.put(tnum,rpc);
    if( rpc._dt instanceof TaskPutKey ) _tasksPutKey.put(tnum,(TaskPutKey)rpc._dt);
  }
  RPC taskGet(int tnum) { return _tasks.get(tnum); }
  void taskRemove(int tnum) {
    _tasks.remove(tnum);
    _tasksPutKey.remove(tnum);
  }
  Collection<RPC> tasks() { return _tasks.values(); }
  int taskSize() { return _tasks.size(); }

  // True if there is a pending PutKey against this Key.  Totally a speed
  // optimization in the case of a large number of pending Gets are flooding
  // the tasks() queue, each needing to scan the tasks queue for pending
  // PutKeys to the same Key.  Legal to always
  private final NonBlockingHashMapLong<TaskPutKey> _tasksPutKey = new NonBlockingHashMapLong<>();
  TaskPutKey pendingPutKey( Key k ) {
    for( TaskPutKey tpk : _tasksPutKey.values() )
      if( k.equals(tpk._key) )
        return tpk;
    return null;
  }

  // The next unique task# sent *TO* the 'this' Node.
  private final AtomicInteger _created_task_ids = new AtomicInteger(1);
  int nextTaskNum() { return _created_task_ids.getAndIncrement(); }


  // ---------------
  // The Work-In-Progress list.  Each item is a UDP packet's worth of work.
  // When the RPCCall to _computed, then it's Completed work instead
  // work-in-progress.  Completed work can be short-circuit replied-to by
  // resending the RPC._dt back.  Work that we're sure the this Node has seen
  // the reply to can be removed - but we must remember task-completion for all
  // time (because UDP packets can be dup'd and arrive very very late and
  // should not be confused with new work).
  private final NonBlockingHashMapLong<RPC.RPCCall> _work = new NonBlockingHashMapLong<>();

  // We must track even dead/completed tasks for All Time (lest a very very
  // delayed UDP packet look like New Work).  The easy way to do this is leave
  // all work packets/RPCs in the _work HashMap for All Time - but this amounts
  // to a leak.  Instead we "roll up" the eldest completed work items, just
  // remembering their completion status.  Task id's older (smaller) than the
  // _removed_task_ids are both completed, and rolled-up to a single integer.
  private final AtomicInteger _removed_task_ids = new AtomicInteger(0);
  // A Golden Completed Task: it's a shared completed task used to represent
  // all instances of tasks that have been completed and are no longer being
  // tracked separately.
  private final RPC.RPCCall _removed_task = new RPC.RPCCall(this);

  RPC.RPCCall has_task( int tnum ) {
    if( tnum <= _removed_task_ids.get() ) return _removed_task;
    return _work.get(tnum);
  }

  // Record a task-in-progress, or return the prior RPC if one already exists.
  // The RPC will flip to "_completed" once the work is done.  The RPC._dtask
  // can be repeatedly ACKd back to the caller, and the _dtask is removed once
  // an ACKACK appears - and the RPC itself is removed once all prior RPCs are
  // also ACKACK'd.
  RPC.RPCCall record_task( RPC.RPCCall rpc ) {
    // Task removal (and roll-up) suffers from classic race-condition, which we
    // fix by a classic Dekker's algo; a task# is always in either the _work
    // HashMap, or rolled-up in the _removed_task_ids counter, or both (for
    // short intervals during the handoff).  We can never has a cycle where
    // it's in neither or else a late UDP may attempt to "resurrect" the
    // already completed task.  Hence we must always check the "removed ids"
    // AFTER we insert in the HashMap (we can check before also, but that's a
    // simple optimization and not sufficient for correctness).
    final RPC.RPCCall x = _work.putIfAbsent(rpc._tsknum,rpc);
    if( x != null ) return x;   // Return pre-existing work
    // If this RPC task# is very old, we just return a Golden Completed task.
    // The task is not just completed, but also we have already received
    // verification that the client got the answer.  So this is just a really
    // old attempt to restart a long-completed task.
    if( rpc._tsknum > _removed_task_ids.get() ) return null; // Task is new
    _work.remove(rpc._tsknum); // Bogus insert, need to remove it
    return _removed_task;      // And return a generic Golden Completed object
  }
  // Record the final return value for a DTask.  Should happen only once.
  // Recorded here, so if the client misses our ACK response we can resend the
  // same answer back.
  void record_task_answer( RPC.RPCCall rpcall ) {
//    assert rpcall._started == 0 || rpcall._dt.hasException();
    rpcall._started = System.currentTimeMillis();
    rpcall._retry = RPC.RETRY_MS; // Start the timer on when to resend
//    AckAckTimeOutThread.PENDING.add(rpcall);
  }
  // Stop tracking a remote task, because we got an ACKACK.
  void remove_task_tracking( int task ) {
    RPC.RPCCall rpc = _work.get(task);
    if( rpc == null ) return;   // Already stopped tracking

    // Atomically attempt to remove the 'dt'.  If we win, we are the sole
    // thread running the dt.onAckAck.  Also helps GC: the 'dt' is done (sent
    // to client and we received the ACKACK), but the rpc might need to stick
    // around a long time - and the dt might be big.
    DTask dt = rpc._dt;         // The existing DTask, if any
    if( dt != null && rpc.CAS_DT(dt,null) ) {
      assert rpc._computed : "Still not done #"+task+" "+dt.getClass()+" from "+rpc._client;
      dt.onAckAck();            // One-time call on stop-tracking
    }
    // Roll-up as many done RPCs as we can, into the _removed_task_ids list
    while( true ) {
      int t = _removed_task_ids.get();   // Last already-removed ID
      RPC.RPCCall rpc2 = _work.get(t+1); // RPC of 1st not-removed ID
      if( rpc2 == null || rpc2._dt != null || !_removed_task_ids.compareAndSet(t,t+1) )
        break;                  // Stop when we hit in-progress tasks
      _work.remove(t+1);        // Else we can remove the tracking now
    }
  }

  // This Node rebooted recently; we can quit tracking prior work history
  void rebooted() {
    _work.clear();
    _removed_task_ids.set(0);
  }

  // Custom Serialization Class: H2OKey need to be built.
  public final AutoBuffer write_impl(AutoBuffer ab) { return _key.write(ab); }
  public final H2ONode read_impl( AutoBuffer ab ) { return intern(H2Okey.read(ab), (short) 0 ); }
  public final AutoBuffer writeJSON_impl(AutoBuffer ab) { return ab.putJSONStr("node",_key.toString()); }
  public final H2ONode readJSON_impl( AutoBuffer ab ) { throw H2O.fail(); }


  public SocketChannelFactory getSocketFactory() {
    return _socketFactory;
  }

  public H2OSecurityManager getSecurityManager() {
    return _security;
  }
}
