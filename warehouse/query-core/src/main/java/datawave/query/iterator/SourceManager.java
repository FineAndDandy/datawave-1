package datawave.query.iterator;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.log4j.Logger;

import com.google.common.collect.Lists;

/**
 * *** CAUTION *** : This is not thread safe and is not expected or intended to be used in such an environment. Doing so will break your implementation.
 * 
 * Design: basic unary tree to support parent/child relationship among delegates of source manager
 * 
 * Purpose: reduce the number of sources when our AST deepcopies nodes specifically for IteratorBuildingVisitor.
 *
 */
public class SourceManager implements SortedKeyValueIterator<Key,Value> {
    private static final Logger log = Logger.getLogger(SourceManager.class);
    protected volatile int sources = 0;
    protected volatile int deepCopiesCalled = 0;
    
    protected long initialSize = 0;
    protected long createdSize = 0;
    
    Queue<SourceManager> sourceQueue;
    
    // objects for seeking
    protected Key lastKey = null;
    protected Range lastRange;
    protected Collection<ByteSequence> columnFamilies = Lists.newArrayList();
    protected boolean inclusive = false;
    
    protected SortedKeyValueIterator<Key,Value> originalSource = null;
    protected SourceManager child = null;
    private IteratorEnvironment originalEnv = null;
    private boolean root = false;
    
    public SourceManager(int initialSize, SortedKeyValueIterator<Key,Value> source) {
        this.initialSize = initialSize;
        child = new SourceManager(source);
        sourceQueue = null;
        sourceQueue = new ConcurrentLinkedQueue<>();
    }
    
    public SourceManager(SortedKeyValueIterator<Key,Value> source) {
        this.initialSize = 0;
        this.child = null;
        originalSource = source;
        sourceQueue = new ConcurrentLinkedQueue<>();
    }
    
    public SourceManager(SourceManager nextSource, boolean root) {
        this(nextSource);
        this.root = root;
    }
    
    protected SourceManager createSource() {
        SourceManager child = new SourceManager(0, originalSource.deepCopy(originalEnv));
        createdSize++;
        return child;
    }
    
    protected void recreateSources(long sizeToCreate) {
        if (null == originalSource) {
            throw new RuntimeException("Original source wasn't configured " + originalSource + " " + originalEnv);
        }
        for (int i = 0; i < sizeToCreate; i++) {
            
            sourceQueue.offer(createSource());
            
        }
    }
    
    protected void setChild(final SourceManager child) {
        this.child = child;
    }
    
    public void setInitialSize(long initialSize) {
        this.initialSize = initialSize;
        if (initialSize > 0)
            recreateSources(1);
    }
    
    @Override
    public void init(SortedKeyValueIterator<Key,Value> source, Map<String,String> options, IteratorEnvironment env) throws IOException {
        
        // started queue
        originalSource = source;
        originalEnv = env;
        if (initialSize > 0)
            recreateSources(1);
        
    }
    
    @Override
    public void next() throws IOException {
        if (null != child) {
            
            reseekIfNeeded();
            
            child.next();
            
            try {
                lastKey = child.getTopKey();
            } catch (Exception e) {
                lastKey = null;
            }
        } else {
            originalSource.next();
            if (originalSource.hasTop()) {
                try {
                    lastKey = originalSource.getTopKey();
                } catch (Exception e) {
                    lastKey = null;
                }
            } else
                lastKey = null;
        }
        
    }
    
    @Override
    public Key getTopKey() {
        Key topKey = null;
        if (null != child) {
            topKey = child.getTopKey();
        } else {
            if (null != originalSource && originalSource.hasTop())
                topKey = originalSource.getTopKey();
        }
        return topKey;
    }
    
    public SortedKeyValueIterator<Key,Value> getOriginalSource() {
        return originalSource;
    }
    
    @Override
    public Value getTopValue() {
        if (null != child) {
            return child.getTopValue();
            
        } else {
            return originalSource.getTopValue();
        }
    }
    
    private void seekFromLast() throws IOException {
        if (null != lastKey) {
            if (log.isTraceEnabled())
                log.trace("reseek from key " + lastKey);
            seek(new Range(lastKey, true, lastRange.getEndKey(), lastRange.isEndKeyInclusive()), columnFamilies, inclusive);
            lastKey = null;
        } else {
            if (log.isTraceEnabled())
                log.trace("reseek from range " + lastRange);
            seek(lastRange, columnFamilies, inclusive);
        }
        
    }
    
    @Override
    public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
        if (null != child) {
            if (log.isDebugEnabled())
                log.debug("DeepCopy at " + sourceQueue.size() + ", deepCopies: " + deepCopiesCalled + ", sources: " + sources + " child seek to " + range);
            child.seek(range, columnFamilies, inclusive);
            
            try {
                lastKey = child.getTopKey();
            } catch (Exception e) {
                lastKey = null;
            }
            
        } else {
            // we are a child source node and must be resought
            
            if (log.isDebugEnabled())
                log.debug("DeepCopy at " + sourceQueue.size() + ", deepCopies: " + deepCopiesCalled + ", sources: " + sources + " original source seek to "
                                + range);
            if (null != originalSource) {
                originalSource.seek(range, columnFamilies, inclusive);
                
                if (originalSource.hasTop()) {
                    try {
                        lastKey = originalSource.getTopKey();
                    } catch (Exception e) {
                        lastKey = null;
                    }
                } else
                    lastKey = null;
            }
            
        }
        lastRange = range;
        this.columnFamilies = columnFamilies;
        this.inclusive = inclusive;
    }
    
    /**
     * TODO: Since we keep track of last key in the bottom source, perhaps we could look at those which are closer when selecting the source. Right now we are
     * given a source and re-use it; however, there is no reason we can't return it for another -- In some experiments this didn't seem fruitful. i.e. it added
     * overhead for very little perceived benefit, but I'm uncertain if that is always the case. It "seems like it should work" but sometimes feels don't
     * translate to realistic performance benefits.
     */
    public SortedKeyValueIterator<Key,Value> deepCopy(IteratorEnvironment env) {
        deepCopiesCalled++;
        if (initialSize > 0) {
            Queue<SourceManager> queue = sourceQueue;
            SourceManager nextSource = null;
            // lazily create the sources.
            if (createdSize < initialSize) {
                nextSource = createSource();
            } else {
                nextSource = queue.poll();
            }
            
            if (log.isDebugEnabled())
                log.debug("DeepCopy at " + sourceQueue.size() + ", deepCopies: " + deepCopiesCalled + ", sources: " + sources);
            
            queue.offer(nextSource);
            
            SourceManager sourcedManager = new SourceManager(null);
            sourcedManager.setChild(nextSource);
            sourcedManager.root = true;
            return sourcedManager;
        } else {
            if (null != child) {
                if (log.isDebugEnabled())
                    log.debug("Child DeepCopy at " + sourceQueue.size() + ", deepCopies: " + deepCopiesCalled + ", sources: " + sources);
                return child.deepCopy(env);
            } else {
                if (log.isDebugEnabled())
                    log.debug("DeepCopy at " + sourceQueue.size() + ", deepCopies: " + deepCopiesCalled + ", sources: " + sources);
                return originalSource.deepCopy(env);
            }
        }
        
    }
    
    protected boolean reseekIfNeeded() {
        if (root) {
            
            Range childRange = null;
            Key childLastKey = null;
            if (null != child) {
                childRange = child.getSourceRange();
                childLastKey = child.getSourceLastKey();
            }
            
            boolean lastKeysNull = lastKey == null;
            lastKeysNull &= childLastKey == null;
            if (log.isTraceEnabled()) {
                log.trace("reseek " + lastRange + " " + childRange + " " + childLastKey + " " + lastKey);
            }
            if (childRange != null && (!lastRange.equals(childRange) && !lastKeysNull && (lastKey == null || !lastKey.equals(childLastKey)))) {
                try {
                    seekFromLast();
                    return true;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return false;
    }
    
    private Key getSourceLastKey() {
        if (null != child) {
            return child.getSourceLastKey();
        } else
            return lastKey;
    }
    
    private Range getSourceRange() {
        if (null != child) {
            return child.getSourceRange();
        } else
            return lastRange;
    }
    
    @Override
    public boolean hasTop() {
        if (null != child) {
            
            if (log.isTraceEnabled()) {
                log.trace("Call has top" + lastRange + " " + getSourceRange() + " " + lastKey + " " + getSourceLastKey());
            }
            reseekIfNeeded();
            
            return child.hasTop();
        } else
            return originalSource.hasTop();
    }
    
    public int getChildrenSize() {
        return sourceQueue.size();
    }
    
    public long getCreatedSize() {
        return createdSize;
    }
    
    public long getSourceSize() {
        return initialSize;
    }
    
}
