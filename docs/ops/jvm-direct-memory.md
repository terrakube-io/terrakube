# JVM direct memory limit

The API, executor and registry images start the JVM with `-XX:MaxDirectMemorySize=64M`.

## Why the images set it

The images are built with Paketo buildpacks. When no direct memory limit is given, the Paketo memory calculator sets `-XX:MaxDirectMemorySize=10M` at startup, whatever the container memory limit is.

Netty 4.2 (Spring Boot 4.1) keeps a few megabytes of direct memory per IO thread, so the HTTP and Redis clients of a service go over 10M on their own. The request that hits the limit fails with:

```
io.netty.util.internal.OutOfDirectMemoryError: failed to allocate 262144 byte(s) of direct memory (used: 10428423, max: 10485760)
```

## Changing the limit

Set `-XX:MaxDirectMemorySize` in `JAVA_TOOL_OPTIONS` on the container:

```yaml
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-XX:MaxDirectMemorySize=256M"
```

The image puts its default in front of the `JAVA_TOOL_OPTIONS` you set, and the last value wins, so yours replaces it. Other options in your `JAVA_TOOL_OPTIONS`, such as `-Xmx`, do not remove the default.

Raise the limit when the error above still appears with `max: 67108864`. That is most likely on nodes with many CPUs and no CPU limit on the container, because Netty starts one IO thread per CPU.

## Effect on the heap

The Paketo memory calculator takes the direct memory limit out of the memory it gives to the heap. Compared with images that ran with 10M, the calculated heap is 54M smaller for the same container memory limit. For example, the registry in a 1G container gets a heap of about 257M instead of 311M.

A container whose memory limit was only just enough before can now fail at startup with a message that ends in:

```
... which is greater than ... available for allocation
```

It starts with `fixed memory regions require` when the container is too small on its own, and with `all memory regions require` when you set `-Xmx` yourself and the heap no longer fits next to the direct memory limit.

Raise the container memory limit by about 64M, lower `-Xmx`, or set a lower `-XX:MaxDirectMemorySize`.

## Checking the limit in use

The startup line `Calculated JVM Memory Configuration: ...` no longer lists `-XX:MaxDirectMemorySize`, because it only lists the values the calculator chose itself. Look at the `Picked up JAVA_TOOL_OPTIONS: ...` line that the JVM prints after it.

## Images built before this default

Images built before this change, every `2.34` release up to `2.34.3` among them, need the same `JAVA_TOOL_OPTIONS` setting to get a limit above 10M.
