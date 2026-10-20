package io.github.fanqiepi.contextpilot.book;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import io.github.fanqiepi.contextpilot.document.StorageService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class BookImportWorker {
    private final BookImportService service;
    private final BookParser parser;
    private final StorageService storage;
    private final BookMapper mapper;
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2),r -> { var t=new Thread(r,"reading-import");t.setDaemon(true);return t; },new ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService parserThread = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),r -> { var t=new Thread(r,"reading-parser");t.setDaemon(true);return t; },new ThreadPoolExecutor.AbortPolicy());
    public BookImportWorker(BookImportService service,BookParser parser,StorageService storage,BookMapper mapper) {
        this.service=service;this.parser=parser;this.storage=storage;this.mapper=mapper;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recover() { mapper.recover(); }
    public void submit(BookImportEntity row) {
        Instant enqueued=Instant.now();
        try {
            queue.execute(() -> {
                if(Duration.between(enqueued,Instant.now()).toSeconds()>=60) { service.fail(row.ownerId,row.id,"QUEUE_TIMEOUT");return; }
                Future<BookParser.Parsed> future;
                try { future=parserThread.submit(() -> {
                    try(InputStream input=storage.open(row.storageKey)) {
                        byte[] bytes=input.readNBytes(20*1024*1024+1);
                        if(!ReadingText.hash(bytes).equals(row.fileHash)) throw ReadingException.conflict("SOURCE_CHANGED","原文件校验失败");
                        return parser.parse(bytes,parser.rule(row.parserId,row.headingLevel),row.headingLevel);
                    }
                }); } catch(RejectedExecutionException full) { service.fail(row.ownerId,row.id,"QUEUE_FULL");return; }
                try { service.parsed(row.ownerId,row.id,future.get(120,TimeUnit.SECONDS)); }
                catch(TimeoutException timeout) { service.fail(row.ownerId,row.id,"PARSE_TIMEOUT"); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt();service.fail(row.ownerId,row.id,"PARSE_INTERRUPTED"); }
                catch(Exception failure) {
                    Throwable cause=failure instanceof ExecutionException ? failure.getCause() : failure;
                    service.fail(row.ownerId,row.id,cause instanceof ReadingException known ? known.code() : "PARSE_FAILED");
                } finally { future.cancel(true); }
            });
        } catch(RejectedExecutionException full) {
            service.fail(row.ownerId,row.id,"QUEUE_FULL");
            throw new ReadingException(429,"QUEUE_FULL","导入队列已满，请稍后重新预览");
        }
    }
    @PreDestroy public void close() { queue.shutdownNow();parserThread.shutdownNow(); }
}
