package org.neo4j.kernel.ha.zookeeper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.neo4j.helpers.Pair;

public class ZooClient implements Watcher
{
    private ZooKeeper zooKeeper;
    private final long storeCreationTime;
    private final long storeId;
    private final int machineId;
    private String sequenceNr;
    
    private long committedTx;
//    private long globalCommittedTx;
    private final String servers;
    private boolean newConnection;
    
    public ZooClient( String servers, int machineId, long storeCreationTime, 
        long storeId, long committedTx )
    {
        this.servers = servers;
        instantiateZooKeeper();
        this.machineId = machineId;
        this.storeCreationTime = storeCreationTime;
        this.storeId = storeId;
        this.committedTx = committedTx;
        this.sequenceNr = "not initialized yet";
//        sequenceNr = setup();
    }
    
    boolean isNewConnection()
    {
        try
        {
            return newConnection;
        }
        finally
        {
            newConnection = false;
        }
    }

    private void instantiateZooKeeper()
    {
        try
        {
            this.zooKeeper = new ZooKeeper( servers, 5000, this );
            this.newConnection = true;
        }
        catch ( IOException e )
        {
            throw new ZooKeeperException( 
                "Unable to create zoo keeper client", e );
        }
    }
    
    private String getRootWithRetries()
    {
        return doZooKeeperJobWithRetries( new Job<String>()
        {
            public String doJob()
            {
                return getRoot();
            }
        } );
    }
    
    private String getRoot()
    {
        String rootPath = "/" + storeCreationTime + "_" + storeId;
        byte[] rootData = null;
        do
        {
            try
            {
                rootData = zooKeeper.getData( rootPath, false, null );
//                ByteBuffer buf = ByteBuffer.wrap( rootData );
//                globalCommittedTx = buf.getLong();
//                if ( globalCommittedTx < committedTx )
//                {
//                    throw new IllegalStateException( "Global committed tx " + 
//                        globalCommittedTx + " while machine[" + machineId + 
//                        "] @" + committedTx );
//                }
                // ok we got the root
                return rootPath;
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NONODE )
                {
                    throw new ZooKeeperException( "Unable to get root node", 
                        e ); 
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
                throw new ZooKeeperException( "Got interrupted", e );
            }
            // try create root
            try
            {
                byte data[] = new byte[0];
                // ByteBuffer buf = ByteBuffer.wrap( data );
                // buf.putLong( committedTx );
                zooKeeper.create( rootPath, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, 
                    CreateMode.PERSISTENT );
            }
            catch ( KeeperException e )
            {
                if ( e.code() != KeeperException.Code.NODEEXISTS )
                {
                    throw new ZooKeeperException( "Unable to create root", e );
                }
            }
            catch ( InterruptedException e )
            {
                Thread.interrupted();
                throw new ZooKeeperException( "Got interrupted", e );
            }
        } while ( rootData == null );
        throw new IllegalStateException();
    }
    
    private String setup()
    {
        try
        {
            String path = getRoot() + "/" + machineId + "_";
            byte[] data = new byte[8];
            ByteBuffer buf = ByteBuffer.wrap( data );
            buf.putLong( committedTx );
            String created = zooKeeper.create( path, data, 
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL_SEQUENTIAL );
            System.out.println( "wrote " + committedTx + " to zookeeper" );
            return created.substring( created.lastIndexOf( "_" ) + 1 );
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to setup", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Setup got interrupted", e );
        }
    }

    public void process( WatchedEvent event )
    {
        System.out.println( new Date() + " Got event: " + event );
        if ( event.getState() == Watcher.Event.KeeperState.Expired )
        {
            instantiateZooKeeper();
        }
        else if ( event.getState() == Watcher.Event.KeeperState.SyncConnected )
        {
            sequenceNr = setup();
        }
    }
    
    public synchronized int getMaster()
    {
        try
        {
            Map<Integer, Pair<Integer, Long>> rawData = new HashMap<Integer, Pair<Integer,Long>>();
            String root = getRoot();
            List<String> children = zooKeeper.getChildren( root, false );
            int currentMasterId = -1;
            int lowestSeq = Integer.MAX_VALUE;
            long highestTxId = -1;
            for ( String child : children )
            {
                int index = child.indexOf( '_' );
                int id = Integer.parseInt( child.substring( 0, index ) );
                int seq = Integer.parseInt( child.substring( index + 1 ) );                
                try
                {
                    byte[] data = zooKeeper.getData( root + "/" + child, false, 
                        null );
                    ByteBuffer buf = ByteBuffer.wrap( data );
                    long tx = buf.getLong();
                    if ( rawData.put( id, new Pair<Integer, Long>( seq, tx ) ) != null )
                    {
                        System.out.println( "warning: " + id + " found more than once" );
                    }
                    if ( tx >= highestTxId )
                    {
                        if ( tx > highestTxId || seq < lowestSeq )
                        {
                            currentMasterId = id;
                            lowestSeq = seq;
                        }
                        highestTxId = tx;
                    }
                }
                catch ( KeeperException inner )
                {
                    if ( inner.code() != KeeperException.Code.NONODE )
                    {
                        throw new ZooKeeperException( "Unabe to get master.", 
                            inner );
                    }
                }
            }
            System.out.println( "getMaster: " + currentMasterId + " based on " + rawData );
            return currentMasterId;
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to get master", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted.", e );
        }
    }
    
    public synchronized void setCommittedTx( long tx )
    {
        System.out.println( "Setting txId=" + tx + " for machine=" + machineId );
        if ( tx <= committedTx )
        {
            throw new IllegalArgumentException( "tx=" + tx + 
                " but committedTx is " + committedTx );
        }
        this.committedTx = tx;
        int masterId = getMaster();
        String root = getRoot();
        String path = root + "/" + machineId + "_" + sequenceNr;
        byte[] data = new byte[8];
        ByteBuffer buf = ByteBuffer.wrap( data );
        buf.putLong( tx );
        try
        {
            zooKeeper.setData( path, data, -1 );
            if ( masterId == machineId )
            {
                zooKeeper.setData( root, data, -1 );
            }
        }
        catch ( KeeperException e )
        {
            throw new ZooKeeperException( "Unable to set current tx", e );
        }
        catch ( InterruptedException e )
        {
            Thread.interrupted();
            throw new ZooKeeperException( "Interrupted...", e );
        }
        
    }
    
    public void shutdown()
    {
        try
        {
            zooKeeper.close();
        }
        catch ( InterruptedException e )
        {
            throw new ZooKeeperException( 
                "Error closing zookeeper connection", e );
        }
    }
    
    private <T> T doZooKeeperJobWithRetries( Job<T> job )
    {
        ZooKeeperException exception = null;
        for ( int i = 0; i < 5; i++ )
        {
            try
            {
                return job.doJob();
            }
            catch ( ZooKeeperException e )
            {
                exception = e;
                try
                {
                    // TODO Just sleep 300ms ?
                    Thread.sleep( 300 );
                }
                catch ( InterruptedException e1 )
                {
                    Thread.interrupted();
                }
            }
        }
        throw exception;
    }
    
    private static interface Job<T>
    {
        T doJob();
    }
}
