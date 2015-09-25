package com.ea.orbit.actors.runtime;

import com.ea.orbit.actors.cluster.NodeAddress;
import com.ea.orbit.actors.extensions.MessageSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Map;

/**
 * A message serializer that uses java serialization.
 */
public class JavaMessageSerializer implements MessageSerializer
{
    public Message deserializeMessage(final Runtime runtime, final InputStream inputStream) throws Exception
    {
        final ObjectInput in = createObjectInput(runtime, inputStream);
        final Message message = new Message();
        message.setMessageType(in.readByte());
        message.setOneWay(in.readBoolean());
        message.setMessageId(in.readInt());
        message.setHeaders((Map) in.readObject());
        message.setPayload(in.readObject());
        return message;
    }

    public void serializeMessage(final Runtime runtime, OutputStream outputStream, Message message) throws Exception
    {
        final ObjectOutput out = createObjectOutput(runtime, outputStream);
        out.writeByte(message.getMessageType());
        out.writeBoolean(message.isOneWay());
        out.writeInt(message.getMessageId());
        out.writeObject(message.getHeaders());
        out.writeObject(message.getPayload());
    }

    private static class ReferenceReplacement implements Serializable
    {
        private static final long serialVersionUID = 1L;

        Class<?> interfaceClass;
        Object id;
        NodeAddress address;
    }

    protected ObjectOutput createObjectOutput(final Runtime runtime, final OutputStream outputStream) throws IOException
    {
        // TODO: move message serialization to a provider (IMessageSerializationProvider)
        // Message(messageId, type, reference, params) and Message(messageId, type, object)
        return new ObjectOutputStream(outputStream)
        {
            {
                enableReplaceObject(true);
            }

            @SuppressWarnings("rawtypes")
            @Override
            protected Object replaceObject(final Object obj) throws IOException
            {
                final ActorReference reference;
                if (!(obj instanceof ActorReference))
                {
                    if (obj instanceof AbstractActor)
                    {
                        reference = ((AbstractActor) obj).reference;
                    }
                    else if (obj instanceof com.ea.orbit.actors.ActorObserver)
                    {
                        com.ea.orbit.actors.ActorObserver objectReference = runtime.getObjectReference(null, (com.ea.orbit.actors.ActorObserver) obj);
                        reference = (ActorReference) objectReference;
                    }
                    else
                    {
                        return super.replaceObject(obj);
                    }
                }
                else
                {
                    reference = (ActorReference) obj;
                }
                ReferenceReplacement replacement = new ReferenceReplacement();
                replacement.address = reference.address;
                replacement.interfaceClass = reference._interfaceClass();
                replacement.id = reference.id;
                return replacement;
            }
        };
    }

    protected ObjectInput createObjectInput(final Runtime runtime, InputStream in) throws IOException
    {
        return new ObjectInputStream(in)
        {
            {
                enableResolveObject(true);
            }

            @SuppressWarnings({"unchecked", "rawtypes"})
            @Override
            protected Object resolveObject(Object obj) throws IOException
            {
                if (obj instanceof ReferenceReplacement)
                {
                    ReferenceReplacement replacement = (ReferenceReplacement) obj;
                    if (replacement.address != null)
                    {
                        return runtime.getRemoteObjectReference(replacement.address, (Class) replacement.interfaceClass, replacement.id);
                    }
                    return runtime.getReference((Class) replacement.interfaceClass, replacement.id);

                }
                return super.resolveObject(obj);
            }
        };
    }


}
