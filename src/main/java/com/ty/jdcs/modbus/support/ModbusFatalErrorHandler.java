package com.ty.jdcs.modbus.support;

import lombok.extern.slf4j.Slf4j;

/**
 * Modbus 致命错误处理器
 *
 * <p>当框架遇到无法在 Java 层恢复的错误（如 JNI 层调用卡死、连续重连超过阈值、通信线程泄漏超阈值等），
 * 该处理器会强制终止当前 JVM 进程，依赖操作系统服务（systemd、Docker restart policy、
 * K8s liveness probe 等）重新拉起应用，从而恢复 Modbus 通信能力。
 *
 * <p><b>为什么用 {@code Runtime.halt()} 而不是 {@code System.exit()}？</b>
 * <ul>
 *   <li>{@code System.exit()} 会触发 shutdown hook → {@code @PreDestroy}
 *       → {@code client.disconnect()}。在致命错误场景下，{@code disconnect()}
 *       很可能正是卡死的源头，执行它会导致 JVM 无法退出。</li>
 *   <li>{@code Runtime.halt()} 直接终止 JVM，跳过 shutdown hook 与 finalizer，
 *       由操作系统回收全部资源，行为接近 {@code kill -9}。</li>
 * </ul>
 *
 * <p><b>使用约束：</b>
 * <ul>
 *   <li>只用于不可恢复的致命错误，可恢复异常请走 {@code ModbusReadException} 等正常异常路径。</li>
 *   <li>本类线程安全，可被多个线程并发调用；首次调用即终止 JVM，后续调用不会执行。</li>
 *   <li>调用后不会返回，调用方无需再做清理。</li>
 * </ul>
 *
 * @Author Tommy
 * @Date 2026/9/27
 */
@Slf4j
public class ModbusFatalErrorHandler {

    public void fatal(String reason, Throwable cause) {
        log.error("=== Modbus 致命错误，进程即将强制退出 ===");
        log.error("原因: {}", reason);
        if (cause != null) {
            log.error("异常: ", cause);
        }
        log.error("进程将通过 Runtime.halt(-1) 强制终止，依赖操作系统服务（systemd、Docker restart policy、K8s liveness probe 等）自动重启");

        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        Runtime.getRuntime().halt(-1);
    }
}
