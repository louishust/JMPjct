import java.net.*;
import java.io.*;
import java.util.*;

public class Proxy extends Thread {
    
    // Where to connect to
    public String mysqlHost = null;
    public int mysqlPort;
    
    // MySql server stuff
    public Socket mysqlSocket = null;
    public InputStream mysqlIn = null;
    public OutputStream mysqlOut = null;
    
    // Client stuff
    public Socket clientSocket = null;
    public InputStream clientIn = null;
    public OutputStream clientOut = null;
    
    // Plugins
    public ArrayList<Proxy_Plugin> plugins = new ArrayList<Proxy_Plugin>();
    
    // Packet Buffer. ArrayList so we can grow/shrink dynamically
    public ArrayList<byte[]> buffer = new ArrayList<byte[]>();
    public int packet_id = 0;
    public int offset = 0;
    
    // Stop the thread?
    public int running = 1;

    // Connection info
    public byte packetType = 0;
    public String schema = "";
    public long sequenceId = 0;
    public String query = "";
    public long affectedRows = 0;
    public long lastInsertId = 0;
    public long statusFlags = 0;
    public long warnings = 0;
    public long errorCode = 0;
    public String sqlState = "";
    public String errorMessage = "";
    public long protocolVersion = 0;
    public String serverVersion = "";
    public long connectionId = 0;
    public long capabilityFlags = 0;
    public long characterSet = 0;
    public long serverCapabilityFlagsOffset = 0;
    public long serverCapabilityFlags = 0;
    public long serverCharacterSet = 0;
    public long clientCapabilityFlags = 0;
    public long clientCharacterSet = 0;
    public String user = "";
    public long clientMaxPacketSize = 0;
    
    // Modes
    public int mode = 0;
    
    // Allow plugins to muck with the modes
    public int nextMode = 0;
    
    public Proxy(Socket clientSocket, String mysqlHost, int mysqlPort, ArrayList<Proxy_Plugin> plugins) {
        this.clientSocket = clientSocket;
        this.mysqlHost = mysqlHost;
        this.mysqlPort = mysqlPort;
        this.plugins = plugins;
        
        try {
            this.clientIn = this.clientSocket.getInputStream();
            this.clientOut = this.clientSocket.getOutputStream();
        
            // Connect to the mysql server on the other side
            this.mysqlSocket = new Socket(this.mysqlHost, this.mysqlPort);
            this.mysqlIn = this.mysqlSocket.getInputStream();
            this.mysqlOut = this.mysqlSocket.getOutputStream();
        }
        catch (IOException e) {
            System.err.print("IOException: "+e+"\n");
            return;
        }
    }

    public void run() {
        //System.err.print("MODE_INIT\n");
        this.mode = MySQL_Flags.MODE_INIT;
        this.nextMode = MySQL_Flags.MODE_READ_HANDSHAKE;
        this.running = 1;
        this.call_plugins();
        this.mode = this.nextMode;

        while (this.running == 1) {
            
            switch (this.mode) {
                case MySQL_Flags.MODE_READ_HANDSHAKE:
                    //System.err.print("MODE_READ_HANDSHAKE\n");
                    this.nextMode = MySQL_Flags.MODE_READ_AUTH;
                    this.read_handshake();
                    break;
                
                case MySQL_Flags.MODE_READ_AUTH:
                    //System.err.print("MODE_READ_AUTH\n");
                    this.nextMode = MySQL_Flags.MODE_READ_AUTH_RESULT;
                    this.read_auth();
                    break;
                
                case MySQL_Flags.MODE_READ_AUTH_RESULT:
                    //System.err.print("MODE_READ_AUTH_RESULT\n");
                    this.nextMode = MySQL_Flags.MODE_READ_QUERY;
                    this.read_auth_result();
                    break;
                
                case MySQL_Flags.MODE_READ_QUERY:
                    //System.err.print("MODE_READ_QUERY\n");
                    this.nextMode = MySQL_Flags.MODE_READ_QUERY_RESULT;
                    this.read_query();
                    break;
                
                case MySQL_Flags.MODE_READ_QUERY_RESULT:
                    //System.err.print("MODE_READ_QUERY_RESULT\n");
                    this.nextMode = MySQL_Flags.MODE_SEND_QUERY_RESULT;
                    this.read_query_result();
                    break;
                
                case MySQL_Flags.MODE_SEND_QUERY_RESULT:
                    //System.err.print("MODE_SEND_QUERY_RESULT\n");
                    this.nextMode = MySQL_Flags.MODE_READ_QUERY;
                    this.send_query_result();
                    break;
                
                default:
                    System.err.print("UNKNOWN MODE "+this.mode+"\n");
                    this.halt();
                    break;
            }
            this.call_plugins();
            this.mode = this.nextMode;
        }
        
        this.mode = MySQL_Flags.MODE_CLEANUP;
        this.nextMode = MySQL_Flags.MODE_CLEANUP;
        //System.err.print("MODE_CLEANUP\n");
        this.call_plugins();
        
        System.err.print("\nExiting thread.\n");
    }
    
    public void halt() {
        this.mode = MySQL_Flags.MODE_CLEANUP;
        this.nextMode = MySQL_Flags.MODE_CLEANUP;
        this.running = 0;
    }
    
    public void call_plugins() {
        for (int i = 0; i < this.plugins.size(); i++) {
            Proxy_Plugin plugin = this.plugins.get(i);
            switch (this.mode) {
                case MySQL_Flags.MODE_INIT:
                    plugin.init(this);
                    break;
                
                case MySQL_Flags.MODE_READ_HANDSHAKE:
                    plugin.read_handshake(this);
                    break;
                
                case MySQL_Flags.MODE_READ_AUTH:
                    plugin.read_auth(this);
                    break;
                
                case MySQL_Flags.MODE_READ_AUTH_RESULT:
                    plugin.read_auth_result(this);
                    break;
                
                case MySQL_Flags.MODE_READ_QUERY:
                    plugin.read_query(this);
                    break;
                
                case MySQL_Flags.MODE_READ_QUERY_RESULT:
                    plugin.read_query_result(this);
                    break;
                
                case MySQL_Flags.MODE_SEND_QUERY_RESULT:
                    plugin.send_query_result(this);
                    break;
                
                case MySQL_Flags.MODE_CLEANUP:
                    plugin.cleanup(this);
                    break;
                
                default:
                    System.err.print("UNKNOWN MODE "+this.mode+"\n");
                    this.halt();
                    break;
            }
        }
    }
    
    public void clear_buffer() {
        this.offset = 0;
        this.packet_id = 0;
        this.buffer.clear();
    }
    
    public void read_full_result_set(InputStream in) {
        // Assume we have the start of a result set already
        this.offset = 4;
        long colCount = this.get_lenenc_int();
        byte[] packet;
        
        for (int i = 0; i < (colCount+1); i++) {
            packet = this.read_packet(this.mysqlIn);
            if (packet == null) {
                this.halt();
                return;
            }
        }
        
        do {
            packet = this.read_packet(this.mysqlIn);
            if (packet == null) {
                this.halt();
                return;
            }
            System.err.print("Reading Row "+this.buffer.size()+"\r");
        } while (packet[4] != MySQL_Flags.EOF);
        System.err.print("\n");
        
        // Do we have more results?
        this.offset=7;
        long statusFlags = this.get_fixed_int(2);
        if ((statusFlags & MySQL_Flags.SERVER_MORE_RESULTS_EXISTS) != 0) {
            this.read_packet(this.mysqlIn);
            this.read_full_result_set(this.mysqlIn);
        }
    }
    
    public byte[] read_packet(InputStream in) {
        int b = 0;
        int size = 0;
        byte[] packet = new byte[3];
        this.packet_id = this.buffer.size();
        
        try {
            // Read size (3) and Sequence id (1)
            b = in.read(packet, 0, 3);
            if (b == -1) {
                this.halt();
                return null;
            }
        }
        catch (IOException e) {
            System.err.print("IOException: "+e+"\n");
            this.halt();
            return null;
        }
        
        this.buffer.add(packet);
        size = (int)this.get_packet_size();
        
        byte[] packet_tmp = new byte[size+4];
        System.arraycopy(packet, 0, packet_tmp, 0, 3);
        packet = packet_tmp;
        packet_tmp = null;
        
        try {
            b = in.read(packet, 3, packet.length-3);
            if (b == -1) {
                this.halt();
                return null;
            }
        }
        catch (IOException e) {
            System.err.print("IOException: "+e+"\n");
            this.halt();
            return null;
        }
        this.buffer.set(this.packet_id, packet);
        return packet;
    }
    
    public void write(OutputStream out) {
        
        for (int i = 0;i < this.buffer.size(); i++) {
            byte[] packet = this.buffer.get(i);
            try {
                out.write(packet);
            }
            catch (IOException e) {
                this.halt();
                System.err.print("IOException: "+e+"\n");
                return;
            }
        }
        this.clear_buffer();
    }
    
    public void read_handshake() {
        this.read_packet(this.mysqlIn);
        
        this.offset = 4;
        this.protocolVersion = this.get_fixed_int(1);
        this.offset += 1; //filler
        this.serverVersion   = this.get_nul_string();
        this.connectionId    = this.get_fixed_int(4);
        this.offset += 8; // challenge-part-1
        this.offset += 1; //filler
        
        this.serverCapabilityFlags = this.get_fixed_int(2);
        
        // Remove Compression and SSL support so we can sniff traffic easily
        
        if ((this.serverCapabilityFlags & MySQL_Flags.CLIENT_COMPRESS) != 0)
            this.serverCapabilityFlags ^= MySQL_Flags.CLIENT_COMPRESS;
        
        if ((this.serverCapabilityFlags & MySQL_Flags.CLIENT_SSL) != 0)
            this.serverCapabilityFlags ^= MySQL_Flags.CLIENT_SSL;
        
        this.offset -= 2;
        this.set_fixed_int(2, this.serverCapabilityFlags);
        this.serverCharacterSet = this.get_fixed_int(1);
        this.statusFlags = this.get_fixed_int(2);

        this.write(this.clientOut);
    }
    
    public void read_auth_result() {
        this.read_packet(this.mysqlIn);
        if (this.packetType != MySQL_Flags.OK)
            this.halt();
        this.write(this.clientOut);
    }
    
    public void read_auth() {
        this.read_packet(this.clientIn);
        
        this.offset = 4;
        this.clientCapabilityFlags = this.get_fixed_int(2);
        
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_PROTOCOL_41) == 0) {
            this.halt();
            return;
        }
        
        this.offset = 4;
        this.clientCapabilityFlags = this.get_fixed_int(4);
        this.offset -= 4;
        // Remove Compression and SSL support so we can sniff traffic easily
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_COMPRESS) != 0)
            this.clientCapabilityFlags ^= MySQL_Flags.CLIENT_COMPRESS;
        
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_SSL) != 0)
            this.clientCapabilityFlags ^= MySQL_Flags.CLIENT_SSL;
            
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_MULTI_STATEMENTS) != 0)
            this.clientCapabilityFlags ^= MySQL_Flags.CLIENT_MULTI_STATEMENTS;
            
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_MULTI_RESULTS) != 0)
            this.clientCapabilityFlags ^= MySQL_Flags.CLIENT_MULTI_RESULTS;
            
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_PS_MULTI_RESULTS) != 0)
            this.clientCapabilityFlags ^= MySQL_Flags.CLIENT_PS_MULTI_RESULTS;
        
        this.set_fixed_int(4, this.clientCapabilityFlags);
    
        this.clientMaxPacketSize = this.get_fixed_int(4);
        this.clientCharacterSet = this.get_fixed_int(1);
        this.offset += 23;
        this.user = this.get_nul_string();
        
        // auth-response
        if ((this.clientCapabilityFlags & MySQL_Flags.CLIENT_SECURE_CONNECTION) != 0)
            this.get_lenenc_string();
        else
            this.get_nul_string();
        
        this.schema = this.get_eop_string();
        
        this.write(this.mysqlOut);
    }
    
    public void read_query() {
        if (this.mode < MySQL_Flags.MODE_READ_AUTH_RESULT)
            return;
        
        byte[] packet = this.read_packet(this.clientIn);
        this.packetType = packet[4];
        this.sequenceId = packet[3];
        
        switch (this.packetType) {
            case MySQL_Flags.COM_QUIT:
                this.halt();
                break;
            
            // Extract out the new default schema
            case MySQL_Flags.COM_INIT_DB:
                this.offset = 5;
                this.schema = this.get_eop_string();
                break;
            
            // Query
            case MySQL_Flags.COM_QUERY:
                this.offset = 5;
                this.query = this.get_eop_string();
                break;
            
            default:
                break;
        }
        
        this.write(this.mysqlOut);
    }
    
    public void read_query_result() {
        if (this.mode < MySQL_Flags.MODE_READ_AUTH_RESULT)
            return;
        
        byte[] packet = this.read_packet(this.mysqlIn);
        this.buffer.get(this.packet_id);
        
        this.get_packet_size();
        this.packetType = packet[4];
        this.sequenceId = packet[3];
        
        switch (this.packetType) {
            case MySQL_Flags.OK:
                if (this.mode >= MySQL_Flags.MODE_READ_AUTH_RESULT) {
                    this.offset = 5;
                    this.affectedRows = this.get_lenenc_int();
                    this.lastInsertId = this.get_lenenc_int();
                    this.statusFlags  = this.get_fixed_int(2);
                    this.warnings     = this.get_fixed_int(2);
                }
                break;
            
            case MySQL_Flags.ERR:
                if (this.mode >= MySQL_Flags.MODE_READ_AUTH_RESULT) {
                    this.offset = 5;
                    this.errorCode    = this.get_fixed_int(2);
                    this.offset++;
                }
                break;
            
            default:
                this.read_full_result_set(this.mysqlIn);
                break;
        }
    }
    
    public void send_query_result(){
        this.write(this.clientOut);
    }
    
    public long get_packet_size() {
        long size = 0;
        int offset = this.offset;
        this.offset = 0;
        size = this.get_fixed_int(3);
        this.offset = offset;
        return size;
    }
    
    public long get_lenenc_int() {
        byte[] packet = this.buffer.get(this.packet_id);
        long value = 0;
        
        // 1 byte int
        if (packet[this.offset] < 251 && packet.length >= (1 + this.offset) ) {
            value = packet[this.offset];
            this.offset += 1;
            value = value & 0xFFL;
            return value;
        }
            
        // 2 byte int
        if (packet[this.offset] == 252 && packet.length >= (3 + this.offset) ) {
            value |= packet[this.offset+2] & 0xFF;
            value <<= 8;
            value |= packet[this.offset+1] & 0xFF;
            
            this.offset += 3;
            return value;
        }
        
        // 3 byte int
        if (packet[this.offset] == 253 && packet.length >= (4 + this.offset) ) {
            value |= packet[this.offset+3] & 0xFF;
            value <<= 8;
            value |= packet[this.offset+2] & 0xFF;
            value <<= 8;
            value |= packet[this.offset+1] & 0xFF;
            
            this.offset += 4;
            return value;
        }
        
        // 8 byte int
        if (packet[this.offset] == 254  && packet.length >= (9 + this.offset) ) {
            value = (packet[this.offset+5] << 0)
                  | (packet[this.offset+6] << 8)
                  | (packet[this.offset+7] << 16)
                  | (packet[this.offset+8] << 24);
                  
            value = value << 32;
                  
            value |= (packet[this.offset+1] << 0)
                  |  (packet[this.offset+2] << 8)
                  |  (packet[this.offset+3] << 16)
                  |  (packet[this.offset+4] << 24);
            
            this.offset += 9;
            return value;
        }
        
        System.err.print("Decoding int at offset "+this.offset+" failed!");
        this.halt();
        return -1;
    }

    public long get_fixed_int(byte[] bytes) {
        long value = 0;
        
        // 1 byte int
        if (bytes.length == 1) {
            value = bytes[0];
            value = value & 0xFFL;
            
            return value;
        }
            
        // 2 byte int
        if (bytes.length == 2) {
            value |= bytes[1] & 0xFF;
            value <<= 8;
            value |= bytes[0] & 0xFF;
            
            return value;
        }
        
        // 3 byte int
        if (bytes.length == 3) {
            value |= bytes[2] & 0xFF;
            value <<= 8;
            value |= bytes[1] & 0xFF;
            value <<= 8;
            value |= bytes[0] & 0xFF;
            
            return value;
        }
        
        // 4 byte int
        if (bytes.length == 4) {
            value |= bytes[3] & 0xFF;
            value <<= 8;
            value |= bytes[2] & 0xFF;
            value <<= 8;
            value |= bytes[1] & 0xFF;
            value <<= 8;
            value |= bytes[0] & 0xFF;
                 
            return value;
        }
        
        // 8 byte int
        if (bytes.length == 8) {
            value = (bytes[4] << 0)
                  | (bytes[5] << 8)
                  | (bytes[6] << 16)
                  | (bytes[7] << 24);
                  
            value = value << 32;
                  
            value |= (bytes[0] << 0)
                  |  (bytes[1] << 8)
                  |  (bytes[2] << 16)
                  |  (bytes[3] << 24);
                  
            if (bytes[7] != 0x00) {
                value = value & 0xFFFFFFFFFFFFFFFFL;
            }
            else if (bytes[6] != 0x00) {
                value = value & 0xFFFFFFFFFFFFFFL;
            }
            else if (bytes[5] != 0x00) {
                value = value & 0xFFFFFFFFFFFFL;
            }
            else if (bytes[4] != 0x00) {
                value = value & 0xFFFFFFFFFFL;
            }
            else if (bytes[3] != 0x00) {
                value = value & 0xFFFFFFFFL;
            }
            else if (bytes[2] != 0x00) {
                value = value & 0xFFFFFFL;
            }
            else if (bytes[1] != 0x00) {
                value = value & 0xFFFFL;
            }
            else {
                value = value & 0xFFL;
            } 
                  
            return value;
        }
        
        System.err.print("Decoding int failed!\n");
        this.halt();
        return -1;
    }
    
    public long get_fixed_int(int size) {
        byte[] packet = this.buffer.get(this.packet_id);
        byte[] bytes = null;
        long value;
        
        if ( packet.length < (size + this.offset))
            return -1;
        
        bytes = new byte[size];
        System.arraycopy(packet, this.offset, bytes, 0, size);
        value = this.get_fixed_int(bytes);
        this.offset += size;
        return value;
    }
    
    public void set_fixed_int(int size, long value) {
        byte[] packet = this.buffer.get(this.packet_id);
        
        if (size == 8 && packet.length >= (this.offset + size)) {
            packet[this.offset+0] = (byte) ((value >>  0) & 0xFF);
            packet[this.offset+1] = (byte) ((value >>  8) & 0xFF);
            packet[this.offset+2] = (byte) ((value >> 16) & 0xFF);
            packet[this.offset+3] = (byte) ((value >> 24) & 0xFF);
            packet[this.offset+4] = (byte) ((value >> 32) & 0xFF);
            packet[this.offset+5] = (byte) ((value >> 40) & 0xFF);
            packet[this.offset+6] = (byte) ((value >> 48) & 0xFF);
            packet[this.offset+7] = (byte) ((value >> 56) & 0xFF);
            
            this.offset += size;
            this.buffer.set(this.packet_id, packet);
            return;
        }
    
        if (size == 4 && packet.length >= (this.offset + size)) {
            packet[this.offset+0] = (byte) ((value >>  0) & 0xFF);
            packet[this.offset+1] = (byte) ((value >>  8) & 0xFF);
            packet[this.offset+2] = (byte) ((value >> 16) & 0xFF);
            packet[this.offset+3] = (byte) ((value >> 24) & 0xFF);
            this.offset += size;
            this.buffer.set(this.packet_id, packet);
            return;
        }
        
        if (size == 3 && packet.length >= (this.offset + size)) {
            packet[this.offset+0] = (byte) ((value >>  0) & 0xFF);
            packet[this.offset+1] = (byte) ((value >>  8) & 0xFF);
            packet[this.offset+2] = (byte) ((value >> 16) & 0xFF);
            this.offset += size;
            this.buffer.set(this.packet_id, packet);
            return;
        }
        
        if (size == 2 && packet.length >= (this.offset + size)) {
            packet[this.offset+0] = (byte) ((value >>  0) & 0xFF);
            packet[this.offset+1] = (byte) ((value >>  8) & 0xFF);
            this.offset += size;
            this.buffer.set(this.packet_id, packet);
            return;
        }
        
        if (size == 1 && packet.length >= (this.offset + size)) {
            packet[this.offset+0] = (byte) ((value >>  0) & 0xFF);
            this.offset += size;
            this.buffer.set(this.packet_id, packet);
            return;
        }
        
        System.err.print("Encoding int "+size+" @ "+this.packet_id+":"+this.offset+" failed!\n");
        this.halt();
        return;
    }
   
    
    public String get_fixed_string(int len) {
        byte[] packet = this.buffer.get(this.packet_id);
        String str = "";
        int i = 0;
        
        for (i = this.offset; i < this.offset+len; i++)
            str += Proxy.int2char(packet[i]);
            
        this.offset += i;
        
        return str;
    }
    
    public String get_eop_string() {
        byte[] packet = this.buffer.get(this.packet_id);
        String str = "";
        int i = 0;
        
        for (i = this.offset; i < packet.length; i++)
            str += Proxy.int2char(packet[i]);
        this.offset += i;
        
        return str;
    }
    
    public String get_nul_string() {
        byte[] packet = this.buffer.get(this.packet_id);
        String str = "";
        
        for (int i = this.offset; i < packet.length; i++) {
            if (packet[i] == 0x00) {
                this.offset += 1;
                break;
            }
            str += Proxy.int2char(packet[i]);
            this.offset += 1;
        }
        
        return str;
    }

    public String get_lenenc_string() {
        byte[] packet = this.buffer.get(this.packet_id);
        String str = "";
        int i = 0;
        int size = (int)this.get_lenenc_int();
        size += this.offset;
        
        for (i = this.offset; i < size; i++) {
            str += Proxy.int2char(packet[i]);
        }
        this.offset = size;
        
        return str;
    }
    
    public static char int2char(byte i) {
        return (char)i;
    }
}