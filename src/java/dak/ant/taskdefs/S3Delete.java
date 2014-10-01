package dak.ant.taskdefs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.tools.ant.BuildException;
import org.jets3t.service.S3Service;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;

import dak.ant.types.S3File;
import dak.ant.types.S3FileSet;

/** Ant task to delete S3 objects selected using an S3FileSet.
  * 
  * @author Tony Seebregts
  *
  */
public class S3Delete extends AWSTask {
       // INSTANCE VARIABLES

       private boolean         dummyRun = false;
       private List<S3FileSet> filesets = new ArrayList<S3FileSet>  ();

       // PROPERTIES

       /** Create method for nested S3 filesets.
         * 
         * @return Initialised S3Fileset that has been added to the internal list of filesets.
         */
       public S3FileSet createS3FileSet() {
              S3FileSet fileset = new S3FileSet();

              filesets.add(fileset);

              return fileset;
       }

       /** Task attribute to execute the delete  as a 'dummy run' to verify that it will do 
         * what is intended. 
         * 
         */
       public void setDummyRun(boolean enabled) {
              this.dummyRun = enabled;
       }

       // IMPLEMENTATION

       /** Deletes all the S3 objects that match the nested S3filesets.
        *   
        */
       @Override
       public void execute() throws BuildException  {
              checkParameters();

              try { AWSCredentials credentials = new AWSCredentials(accessId, secretKey);
                    S3Service      service     = new RestS3Service(credentials);
                    Set<S3File>    list        = new ConcurrentSkipListSet<S3File>();

                    // ... match on filesets

                    for (S3FileSet fileset: filesets) {
                        Iterator<S3File> ix = fileset.iterator(service); 

                        while (ix.hasNext()) {
                              list.add(ix.next());
                        }  
                    }

                    if (list.isEmpty()) {
                       log("Delete list is empty - nothing to do.");
                       return;
                    }

                    // ... delete objects in list

                    Map<String,S3Bucket> buckets = new HashMap<String,S3Bucket>();
                    S3Bucket             bucket;
                    S3Object             object;

                    log("Deleting " + list.size() + " objects");

                    for (S3File file: list) {
                        // ... re-use buckets just in case they ever become heavyweight objects

                        if ((bucket = buckets.get(file.getBucket())) == null) {
                           bucket = new S3Bucket(file.getBucket());

                           buckets.put(file.getBucket(),bucket);
                        }

                        // ... go dog go !

                        object = new S3Object(bucket,file.getKey());

                        if (dummyRun) {
                           log(DUMMY_RUN + " Deleted '[" + object.getBucketName() + "][" + object.getKey() + "'");
                        } else { 
                            service.deleteObject(object.getBucketName(), object.getKey());

                            if (verbose)
                                log("Deleted '[" + object.getBucketName() + "][" + object.getKey() + "']");
                        }
                    }
              } catch(BuildException x) {
                  throw x;
              } catch(ServiceException x) {
                  throw new BuildException(x.getErrorMessage());
              } catch (Exception x) {
                  throw new BuildException(x);
              }
       }

}
