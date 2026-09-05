package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServlet;

import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;

/// Base for every servlet in these applications. It exists to take [Serializable] back off them.
///
/// [HttpServlet] implements [Serializable] because the Servlet API lets a container passivate a servlet instance, and none of ours may be: they are built by
/// the injector and hold their collaborators directly, so an instance restored from a stream would be a working servlet whose service, store or rendered page
/// came from that stream. Both hooks below refuse, and refuse on behalf of every subclass — serialisation runs them once per class in the hierarchy, so the
/// one declared here fires for the subclass's stream too.
///
/// Extend this rather than [HttpServlet] directly. A servlet holding no injected state is no exception: it is one edit away from holding some, and the edit
/// that adds the field is not the one that remembers this.
public abstract class BaseHttpServlet extends HttpServlet {
    @Serial
    private static final long serialVersionUID = 1L;

    @Serial
    private void readObject(ObjectInputStream in) throws NotSerializableException {
        throw new NotSerializableException(getClass().getName());
    }

    @Serial
    private void writeObject(ObjectOutputStream out) throws NotSerializableException {
        throw new NotSerializableException(getClass().getName());
    }
}
