package com.concurrent_ruby.ext;

import java.io.IOException;

import org.jruby.Ruby;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyObject;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.load.Library;
import org.jruby.runtime.Block;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.ThreadContext;
import org.jruby.util.unsafe.UnsafeHolder;

public class SynchronizationLibrary implements Library {

    private static final ObjectAllocator JRUBY_OBJECT_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klazz) {
            return new JRubyObject(runtime, klazz);
        }
    };

    private static final ObjectAllocator OBJECT_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klazz) {
            return new Object(runtime, klazz);
        }
    };

    private static final ObjectAllocator ABSTRACT_LOCKABLE_OBJECT_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klazz) {
            return new AbstractLockableObject(runtime, klazz);
        }
    };

    private static final ObjectAllocator JRUBY_LOCKABLE_OBJECT_ALLOCATOR = new ObjectAllocator() {
        public IRubyObject allocate(Ruby runtime, RubyClass klazz) {
            return new JRubyLockableObject(runtime, klazz);
        }
    };

    public void load(Ruby runtime, boolean wrap) throws IOException {
        RubyModule synchronizationModule = runtime.
                defineModule("Concurrent").
                defineModuleUnder("Synchronization");

        defineClass(runtime, synchronizationModule, "AbstractObject", "JRubyObject",
                JRubyObject.class, JRUBY_OBJECT_ALLOCATOR);

        defineClass(runtime, synchronizationModule, "JRubyObject", "Object",
                Object.class, OBJECT_ALLOCATOR);

        defineClass(runtime, synchronizationModule, "Object", "AbstractLockableObject",
                AbstractLockableObject.class, ABSTRACT_LOCKABLE_OBJECT_ALLOCATOR);

        defineClass(runtime, synchronizationModule, "AbstractLockableObject", "JRubyLockableObject",
                JRubyLockableObject.class, JRUBY_LOCKABLE_OBJECT_ALLOCATOR);
    }

    private RubyClass defineClass(Ruby runtime, RubyModule namespace, String parentName, String name,
                                  Class javaImplementation, ObjectAllocator allocator) {
        final RubyClass parentClass = namespace.getClass(parentName);

        if (parentClass == null) {
            System.out.println("not found " + parentName);
            throw runtime.newRuntimeError(namespace.toString() + "::" + parentName + " is missing");
        }

        final RubyClass newClass = namespace.defineClassUnder(name, parentClass, allocator);
        newClass.defineAnnotatedMethods(javaImplementation);
        return newClass;
    }

    @JRubyClass(name = "JRubyObject", parent = "AbstractObject")
    public static class JRubyObject extends RubyObject {
        public JRubyObject(Ruby runtime, RubyClass metaClass) {
            super(runtime, metaClass);
        }

        @JRubyMethod
        public IRubyObject initialize(ThreadContext context) {
            return this;
        }

        @JRubyMethod(name = "full_memory_barrier", visibility = Visibility.PRIVATE)
        public IRubyObject fullMemoryBarrier(ThreadContext context) {
            if (UnsafeHolder.U == null) {
                // We are screwed
                throw new UnsupportedOperationException();
            } else if (UnsafeHolder.SUPPORTS_FENCES)
                UnsafeHolder.fullFence();
            else {
                // TODO (pitr 06-Sep-2015): enforce Java 8
                throw new UnsupportedOperationException();
            }
            return context.nil;
        }

        @JRubyMethod(name = "instance_variable_get_volatile", visibility = Visibility.PROTECTED)
        public IRubyObject instanceVariableGetVolatile(ThreadContext context, IRubyObject name) {
            if (UnsafeHolder.U == null) {
                synchronized (this) {
                    // TODO (pitr 06-Sep-2015): Possibly dangerous, there may be a deadlock here
                    // TODO (pitr 08-Sep-2015): maybe remove the branch since full_memory_barrier is not supported anyway
                    return instance_variable_get(context, name);
                }
            } else if (UnsafeHolder.SUPPORTS_FENCES) {
                // ensure we see latest value
                UnsafeHolder.loadFence();
                return instance_variable_get(context, name);
            } else {
                throw new UnsupportedOperationException();
            }
        }

        @JRubyMethod(name = "instance_variable_set_volatile", visibility = Visibility.PROTECTED)
        public IRubyObject InstanceVariableSetVolatile(ThreadContext context, IRubyObject name, IRubyObject value) {
            if (UnsafeHolder.U == null) {
                synchronized (this) {
                    return instance_variable_set(name, value);
                }
            } else if (UnsafeHolder.SUPPORTS_FENCES) {
                final IRubyObject result = instance_variable_set(name, value);
                // ensure we make latest value visible
                UnsafeHolder.storeFence();
                return result;
            } else {
                throw new UnsupportedOperationException();
            }
        }
    }

    @JRubyClass(name = "Object", parent = "JRubyObject")
    public static class Object extends JRubyObject {

        public Object(Ruby runtime, RubyClass metaClass) {
            super(runtime, metaClass);
        }
    }

    @JRubyClass(name = "AbstractLockableObject", parent = "Object")
    public static class AbstractLockableObject extends Object {

        public AbstractLockableObject(Ruby runtime, RubyClass metaClass) {
            super(runtime, metaClass);
        }
    }

    @JRubyClass(name = "JRubyLockableObject", parent = "AbstractLockableObject")
    public static class JRubyLockableObject extends JRubyObject {

        public JRubyLockableObject(Ruby runtime, RubyClass metaClass) {
            super(runtime, metaClass);
        }

        @JRubyMethod(name = "synchronize", visibility = Visibility.PROTECTED)
        public IRubyObject rubySynchronize(ThreadContext context, Block block) {
            synchronized (this) {
                return block.yield(context, null);
            }
        }

        @JRubyMethod(name = "ns_wait", optional = 1, visibility = Visibility.PROTECTED)
        public IRubyObject nsWait(ThreadContext context, IRubyObject[] args) {
            Ruby runtime = context.runtime;
            if (args.length > 1) {
                throw runtime.newArgumentError(args.length, 1);
            }
            Double timeout = null;
            if (args.length > 0 && !args[0].isNil()) {
                timeout = args[0].convertToFloat().getDoubleValue();
                if (timeout < 0) {
                    throw runtime.newArgumentError("time interval must be positive");
                }
            }
            if (Thread.interrupted()) {
                throw runtime.newConcurrencyError("thread interrupted");
            }
            boolean success = false;
            try {
                success = context.getThread().wait_timeout(this, timeout);
            } catch (InterruptedException ie) {
                throw runtime.newConcurrencyError(ie.getLocalizedMessage());
            } finally {
                // An interrupt or timeout may have caused us to miss
                // a notify that we consumed, so do another notify in
                // case someone else is available to pick it up.
                if (!success) {
                    this.notify();
                }
            }
            return this;
        }

        @JRubyMethod(name = "ns_signal", visibility = Visibility.PROTECTED)
        public IRubyObject nsSignal(ThreadContext context) {
            notify();
            return this;
        }

        @JRubyMethod(name = "ns_broadcast", visibility = Visibility.PROTECTED)
        public IRubyObject nsBroadcast(ThreadContext context) {
            notifyAll();
            return this;
        }
    }
}
