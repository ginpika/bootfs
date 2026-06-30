package cc.ginpika.bootfs.metrics;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AppStartupTracker {

    private volatile long readyTime = System.currentTimeMillis();

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        this.readyTime = System.currentTimeMillis();
    }

    public long getReadyTime() {
        return readyTime;
    }
}
