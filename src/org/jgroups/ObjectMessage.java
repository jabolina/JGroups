
package org.jgroups;


import org.jgroups.util.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * A {@link Message} with an object as payload. The object won't get serialized until sent by the transport.
 * <br/>
 * Note that the object passed to the constructor (or set with {@link #setObject(Object)}) must not be changed after
 * the creation of an {@link ObjectMessage}.
 * @since  5.0
 * @author Bela Ban
 */
public class ObjectMessage extends BaseMessage {
    protected Object obj; // either a SizeStreamable or wrapped into a (SizeStreamable) ObjectWrapper
    private boolean isStreamable;


    public ObjectMessage() {
        this(null);
    }

    /**
    * Constructs a message given a destination address
    * @param dest The Address of the receiver. If it is null, then the message is sent to the group. Otherwise, it is
    *             sent to a single member.
    */
    public ObjectMessage(Address dest) {
        this(dest, null);
    }



   /**
    * Constructs a message given a destination address and the payload object
    * @param dest The Address of the receiver. If it is null, then the message is sent to the group. Otherwise, it is
    *             sent to a single member.
    * @param obj To be used as payload.
    */
    public ObjectMessage(Address dest, Object obj) {
        super(dest);
        setObject(obj);
    }



    public Supplier<Message> create()                             {return ObjectMessage::new;}
    public short             getType()                            {return Message.OBJ_MSG;}
    public boolean           hasPayload()                         {return obj != null;}
    public boolean           hasArray()                           {return false;}
    public int               getOffset()                          {return 0;}
    public int               getLength()                          {return obj != null? objSize() : 0;}
    public byte[]            getArray()                           {throw new UnsupportedOperationException();}
    public ObjectMessage     setArray(byte[] b, int off, int len) {throw new UnsupportedOperationException();}
    public ObjectMessage     setArray(ByteArray buf)              {throw new UnsupportedOperationException();}

    /** Sets the object. If the object doesn't implement {@link SizeStreamable}, or is a primitive type,
     * it will be wrapped into an {@link ObjectWrapper} (which does implement SizeStreamable)
     */
    public ObjectMessage setObject(Object obj) {
        if (obj != null && Util.isPrimitiveType(obj)) {
            this.obj = obj;
            isStreamable = false;
            return this;
        }

        if (obj == null || obj instanceof SizeStreamable)
            this.obj=obj;
        else
            this.obj=new ObjectWrapper(obj);

        isStreamable = true;
        return this;
    }


    public <T extends Object> T getObject() {
        if(obj == null)
            return null;
        if(obj instanceof ObjectWrapper)
            return ((ObjectWrapper)obj).getObject();
        return (T)obj;
    }


    public int size() {
        return super.size() + objSize();
    }


    public void writePayload(DataOutput out) throws IOException {
        out.writeByte(isStreamable ? 1 : 0);
        if (isStreamable) Util.writeGenericStreamable((SizeStreamable) obj, out);
        else Util.primitiveToStream(obj, out);
    }

    public void readPayload(DataInput in) throws IOException, ClassNotFoundException {
        isStreamable = in.readByte() == 1;
        if (isStreamable) this.obj=Util.readGenericStreamable(in);
        else this.obj=Util.primitiveFromStream(in);
    }

    @Override protected Message copyPayload(Message copy) {
        if(obj != null)
            copy.setObject(obj);
        return copy;
    }

    public String toString() {
        return super.toString() + String.format(", obj: %s", obj);
    }

    protected int objSize() {
        int size = Global.BYTE_SIZE;

        if (isStreamable) return size + Util.size((SizeStreamable) obj);
        return size + Util.sizePrimitive(obj);
    }
}
