package dk.mekbits.bluemapworldscan.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects what the addon logs while it is open. */
public final class LogCapture implements AutoCloseable {

    private final Logger logger = Logger.getLogger("dk.mekbits.bluemapworldscan");
    private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    public LogCapture() {
        logger.addHandler(handler);
    }

    /** The messages logged at the given level or above, oldest first. */
    public List<String> messages(Level atLeast) {
        synchronized (records) {
            return records.stream()
                    .filter(record -> record.getLevel().intValue() >= atLeast.intValue())
                    .map(LogRecord::getMessage)
                    .toList();
        }
    }

    public List<String> warnings() {
        return messages(Level.WARNING);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
