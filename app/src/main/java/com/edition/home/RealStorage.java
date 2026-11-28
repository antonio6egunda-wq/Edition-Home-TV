package com.edition.home;

import android.os.StatFs;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * RAM e armazenamento lidos direto do kernel: /proc/meminfo, /proc/partitions,
 * /proc/mounts e statfs. Não usa as informações que o firmware mostra nas configurações
 * (ActivityManager, Build, props), então um firmware que informa valores falsos
 * não altera o que aparece aqui.
 */
public final class RealStorage {

    public static final class Disk {
        public final String name;
        public final long bytes;

        Disk(String name, long bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    public static final class Partition {
        public final String device;
        public final String mount;
        public final long total;
        public final long free;

        Partition(String device, String mount, long total, long free) {
            this.device = device;
            this.mount = mount;
            this.total = total;
            this.free = free;
        }
    }

    /** Tipos de sistema de arquivos de armazenamento real (ignora tmpfs, sdcardfs, fuse, proc...). */
    private static final String[] BLOCK_FS = {"ext2", "ext3", "ext4", "f2fs", "vfat", "exfat", "ntfs", "fuseblk"};

    private RealStorage() {
    }

    /** {total, disponível} da RAM em bytes, de /proc/meminfo. Zeros se não der para ler. */
    public static long[] memory() {
        long total = 0;
        long free = 0;
        long buffers = 0;
        long cached = 0;
        long available = -1;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) {
                    total = kb(line);
                } else if (line.startsWith("MemFree:")) {
                    free = kb(line);
                } else if (line.startsWith("Buffers:")) {
                    buffers = kb(line);
                } else if (line.startsWith("Cached:")) {
                    cached = kb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    available = kb(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            return new long[]{0, 0};
        }
        if (available < 0) {
            // Kernels antigos (3.x do Android 5) não têm MemAvailable: estima pelo que pode ser liberado.
            available = free + buffers + cached;
        }
        return new long[]{total * 1024L, available * 1024L};
    }

    private static long kb(String line) {
        return Long.parseLong(line.trim().split("\\s+")[1]);
    }

    /** Discos físicos (eMMC, SD, USB) com o tamanho que o kernel informa em /proc/partitions. */
    public static List<Disk> disks() {
        List<Disk> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/partitions"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 4) {
                    continue;
                }
                String name = p[3];
                if (name.matches("mmcblk\\d+|sd[a-z]+|nvme\\d+n\\d+")) {
                    try {
                        out.add(new Disk(name, Long.parseLong(p[2]) * 1024L));
                    } catch (NumberFormatException ignored) {
                        // linha fora do formato esperado
                    }
                }
            }
        } catch (IOException ignored) {
            // sem acesso a /proc/partitions: a lista fica vazia
        }
        return out;
    }

    /** Partições de armazenamento montadas, com tamanho e espaço livre pelo statfs. */
    public static List<Partition> partitions() {
        List<Partition> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\s+");
                if (p.length < 3) {
                    continue;
                }
                String device = p[0];
                String mount = p[1];
                if (!isBlockFs(p[2]) || !seen.add(device)) {
                    continue;
                }
                try {
                    StatFs fs = new StatFs(mount);
                    out.add(new Partition(device, mount, fs.getTotalBytes(), fs.getAvailableBytes()));
                } catch (IllegalArgumentException ignored) {
                    // ponto de montagem sem leitura
                }
            }
        } catch (IOException ignored) {
            // sem /proc/mounts: lista vazia
        }
        return out;
    }

    private static boolean isBlockFs(String type) {
        for (String t : BLOCK_FS) {
            if (t.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** Armazenamento em base 1000 (como os fabricantes): GB ou MB. Null se zero. */
    public static String format(long bytes) {
        if (bytes <= 0) {
            return null;
        }
        if (bytes >= 1000000000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1e9);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1e6);
    }

    /** RAM em base 1024 (como o sistema): GB ou MB. Null se zero. */
    public static String formatBinary(long bytes) {
        if (bytes <= 0) {
            return null;
        }
        if (bytes >= 1073741824L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1048576.0);
    }
}
