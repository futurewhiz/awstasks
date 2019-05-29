package dak.ant.taskdefs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;
import org.jets3t.service.S3Service;
import org.jets3t.service.ServiceException;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.security.AWSCredentials;

import dak.ant.types.S3File;
import dak.ant.types.S3FileSet;

/** Ant task do do bucket-to-bucket copy.
  *  
  * @author Chris Stewart
  *
  */

// TS: Removed non-fileset copy - no longer required since FileName selector supports regular expressions 
//     as of Ant 1.8 and S3FileSet now supports include/exclude patterns.
//     
// TS: Added automatic bucket creation.

public class S3Copy extends AWSTask {
       // INSTANCE VARIABLES
    
       private String          bucket;
       private List<S3FileSet> filesets = new ArrayList<S3FileSet>();
       private boolean         dummyRun = false;

       // PROPERTIES

       /** Sets the destination S3 bucket.
         * 
         */
       public void setBucket(String bucket) {
              this.bucket = bucket;
       }

       /** Create method for nested S3 filesets.
         * 
         * @return Initialised S3Fileset that has been added to the internal list of filesets.
         */
       public S3FileSet createS3FileSet() {
              S3FileSet fileset = new S3FileSet();

              filesets.add(fileset);

              return fileset;
       }

      /** Task attribute to execute the copy as a 'dummy run' to verify that it will do 
         * what is intended. 
         * 
         */
       public void setDummyRun(boolean enabled) { 
              this.dummyRun = enabled;
       }

       // IMPLEMENTATION

       /** Checks that the AWS credentials and the destination bucket have been initialised.
         * 
         */
       @Override
       protected void checkParameters() throws BuildException {
                 super.checkParameters();

                 if (bucket == null) {
                    throw new BuildException("'bucket' task attribute must be set");
                 }
       }

       /** Copies the S3 objects in the filesets across to the task 'bucket' attribute. 
        * <p>
        * The destination bucket is created if it does not already exist.
        */
       @Override
       public void execute() throws BuildException {
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
                       log("Copy list is empty - nothing to do.");
                       return;
                    }

                    // ... copy objects in list

                    log("Copying " + list.size() + " objects");

                    for (S3File file: list) {
                        S3Object object = new S3Object(file.getKey());

                        if (dummyRun) {
                           log(DUMMY_RUN + " Copied '" + file.getBucket() + "::" + file.getKey() + "' to '" + bucket + "::" + object.getKey() + "'");
                        } else { 
                           service.copyObject(file.getBucket(),file.getKey(),bucket,object,true);

                           if (verbose)
                               log("Copied '" + file.getBucket() + "::" + file.getKey() + "' to '" + bucket + "::" + object.getKey() + "'");
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
