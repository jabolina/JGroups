package org.jgroups.demos;

import org.jgroups.*;
import org.jgroups.blocks.MethodCall;
import org.jgroups.blocks.Request;
import org.jgroups.blocks.RequestOptions;
import org.jgroups.blocks.RpcDispatcher;
import org.jgroups.util.ProxyAddress;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;

import java.lang.reflect.Method;

/** Demos RELAY. Create 2 *separate* clusters with RELAY as top protocol. Each RELAY has bridge_props="tcp.xml" (tcp.xml
 * needs to be present). Then start 2 instances in the first cluster and 2 instances in the second cluster. They should
 * find each other, and typing in a window should send the text to everyone, plus we should get 4 responses.
 * @author Bela Ban
 */
public class RelayDemoRpc extends ReceiverAdapter {
    JChannel ch;
    RpcDispatcher disp;
    Address local_addr;




    public static void main(String[] args) throws Exception {
        String props="udp.xml";
        String name=null;


        for(int i=0; i < args.length; i++) {
            if(args[i].equals("-props")) {
                props=args[++i];
                continue;
            }
            if(args[i].equals("-name")) {
                name=args[++i];
                continue;
            }
            System.out.println("RelayDemo [-props props] [-name name]");
            return;
        }
        RelayDemoRpc demo=new RelayDemoRpc();
        demo.start(props, name);
    }

    public void start(String props, String name) throws Exception {
        ch=new JChannel(props);
        if(name != null)
            ch.setName(name);
        disp=new RpcDispatcher(ch, null, this, this);
        ch.connect("RelayDemo");
        local_addr=ch.getAddress();

        MethodCall call=new MethodCall(getClass().getMethod("handleMessage", String.class, Address.class));
        for(;;) {
            String line=Util.readStringFromStdin(": ");
            call.setArgs(new Object[]{line, local_addr});
            RspList rsps=disp.callRemoteMethods(null, call, new RequestOptions(Request.GET_ALL, 5000).setAnycasting(true));
            for(Rsp rsp: rsps.values())
                System.out.println("<< " + rsp.getValue() + " from " + rsp.getSender());
        }
    }

    public static String handleMessage(String msg, Address sender) {
        System.out.println("<< " + msg + " from " + sender);
        return "this is a response";
    }


    public void viewAccepted(View new_view) {
        System.out.println("view: " + print(new_view));
    }


    static String print(View view) {
        StringBuilder sb=new StringBuilder();
        boolean first=true;
        sb.append(view.getViewId()).append(": ");
        for(Address mbr: view.getMembers()) {
            if(first)
                first=false;
            else
                sb.append(", ");
            sb.append(mbr);
            if(mbr instanceof ProxyAddress)
               sb.append("(p)");
        }
        return sb.toString();
    }
}