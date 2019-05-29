package dak.ant.taskdefs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.activation.MimetypesFileTypeMap;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.LogLevel;

import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.acl.GroupGrantee;
import org.jets3t.service.acl.Permission;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.utils.FileComparer;
import org.jets3t.service.utils.FileComparerResults;

/** This class provides basic S3 actions as an Ant task.
  * 
  * @author D. Kavanagh
  */
public class S3Upload extends AWSTask  {
       // INSTANCE VARIABLES

       private String        bucket;
       private String        prefix            = "";
       private boolean       publicRead        = false;
       private List<FileSet> filesets          = new ArrayList<FileSet>();
       private boolean       cacheNeverExpires = false;
       private String        mimeTypesFile     = null;
       private boolean       uploadAll         = false;
       private boolean       uploadNew         = false;
       private boolean       uploadChanged     = false;
       private boolean       dummyRun          = false;

       private MimetypesFileTypeMap mimeTypesMap;
       private AccessControlList bucketAcl;

       // PROPERTIES

       /** Required task attribute - sets the S3 bucket to which to upload.
         *  
         */
       public void setBucket(String bucket) {
              this.bucket = bucket;
       }

       /** Optional task attribute to set a prefix ('folder equivalent') within the S3 bucket. 
         * The default prefix is "".
         *  
         */
       public void setPrefix(String prefix) {
              this.prefix = prefix;
       }

       /** Create method for nested Ant filesets that specify the file for upload.
         *  
         */
       public void addFileset(FileSet set) {
              filesets.add(set);
       }

       /** Sets the access for uploaded S3 objects to 'public read-only'. The default value is <code>false</code> i.e. private.
         * 
         */
       public void setPublicRead(boolean on) {
              this.publicRead = on;
       }

       /** Sets the cache expiry meta-data for uploaded S3 objects to '1 year'.
         * 
         */
       public void setCacheNeverExpires(boolean cacheNeverExpires) {
              this.cacheNeverExpires = cacheNeverExpires;
       }

       /** Sets the MIME types file from which to get the Content-Type meta-data for uploaded S3 objects.
         * 
         */
       public void setMimeTypesFile(String mimeTypesFile) {
              this.mimeTypesFile = mimeTypesFile;
       }

       /** Accepts a comma delimited set of upload options:
         * <ul>
         * <li>all - uploads all objects that match the selection criteria
         * <li>new - uploads any objects that match the selection criteria that do
         *           not already exist in the bucket
         * <li>changed - uploads any objects that match the selection criteria
         *               that have changed compared to the existing existing copy in the bucket
         * </ul>
         * 
         * @param upload Comma separated list of upload options. Defaults to 'all'.
         */
       public void setUpload(String upload) {
              if (upload == null)
                 return;

              String[] tokens = upload.split(",");

              for (String token: tokens) {
                  String _token = token.trim();

                  if ("all".equalsIgnoreCase(_token))
                      uploadAll = true;
                  else if ("new".equalsIgnoreCase(_token))
                      uploadNew = true;
                  else if ("changed".equalsIgnoreCase(_token))
                      uploadChanged = true;
              }
       }

       /** Task attribute to execute the synchronize  as a 'dummy run' to verify that it will do 
         * what is intended. 
         * 
         */
       public void setDummyRun(boolean enabled) {
              this.dummyRun = enabled;
       }

       // IMPLEMENTATION

       /** Check that the AWS access credentials have been initialised and warns if the upload
         * fileset is empty.
         * 
         * @exception BuildException if any of the attributes is invalid.
         */
       protected void checkParameters() throws BuildException {
                 super.checkParameters();

                 if ((bucket == null) || bucket.matches("\\s*"))
                    throw new BuildException("'bucket' attribute must be set");

                 if (filesets == null) {
                    log("No fileset was provided, doing nothing",LogLevel.WARN.getLevel());
                    return;
                 }
       }

       /** Uploads the files selected by the fileset selectors.
         * 
         */
       public void execute() throws BuildException {
              checkParameters();

              try { // ... initialise

                    AWSCredentials credentials = new AWSCredentials(accessId, secretKey);
                    RestS3Service  service     = new RestS3Service(credentials);
                    S3Bucket       bucket      = new S3Bucket(this.bucket);

                    if (publicRead) {
                       bucketAcl = service.getBucketAcl(bucket);

                       bucketAcl.grantPermission(GroupGrantee.ALL_USERS,Permission.PERMISSION_READ);
                    }

                    if (mimeTypesFile != null)
                       mimeTypesMap = new MimetypesFileTypeMap(mimeTypesFile);
                    else
                       mimeTypesMap = new MimetypesFileTypeMap();

                    // ... upload

                    for (FileSet fs: filesets) {
                        try { // ... create upload list

                              DirectoryScanner ds    = fs.getDirectoryScanner(getProject());
                              File             dir   = fs.getDir(getProject());
                              String[]         files = ds.getIncludedFiles();
                              List<File>       list  = new ArrayList<File>();

                              if (uploadAll || (!uploadNew && !uploadChanged)) { 
                                 for (String file: files) {
                                     list.add(new File(dir,file));
                                 }
                              } else {
                                 FileComparer              fc      = FileComparer.getInstance();
                                 Map<String,File>          map     = buildFileMap(dir,files,prefix);
                                 Map<String,StorageObject> objects = fc.buildObjectMap(service,bucket.getName(),"",false,null);
                                 FileComparerResults       rs      = fc.buildDiscrepancyLists(map,objects);

                                 if (uploadNew) {
                                    for (String key: rs.onlyOnClientKeys) {
                                        list.add(map.get(key));
                                    }
                                 }

                                 if (uploadChanged) {
                                    for (String key: rs.updatedOnClientKeys) {
                                        list.add(map.get(key));
                                    }
                                 }
                              }

                              // ... upload files

                              if (list.isEmpty())
                                  log("Upload list is empty - nothing to do",LogLevel.WARN.getLevel());
                              else {
                                  log("Uploading " + list.size() + " files from " + dir.getCanonicalPath());

                                  for (File file: list) { 
                                      upload(service, bucket, dir, file);
                                  }
                              }
                        }  catch (BuildException x) {
                            if (failOnError)
                               throw x;

                            log("Error uploading files to Amazon S3 [" + x.getMessage() + "]", LogLevel.ERR.getLevel());
                        }
                    }
              } catch (BuildException x) {
                  throw x;
              } catch (Exception x) {
                  throw new BuildException(x);
              }
       }

       /** Utility method to upload a single file.
         *  
         * @param service  Initialises S3 service.
         * @param bucket   Source bucket.
         * @param root     'root' directory for file list. Used to match against S3 object list.
         * @param file     File to upload.
         * 
         * @throws Exception
         */
       private void upload(RestS3Service service,S3Bucket bucket,File root,File file) throws Exception {
               // ... validate

               if (!file.exists()) {
                  log("File '" + file.getPath() + "' does not exist",LogLevel.WARN.getLevel());
                  return;
               }

               // ... normalise

               AccessControlList acl         = publicRead ? this.bucketAcl : null;
               String            contentType = mimeTypesMap.getContentType(file);
               String            filepath    = normalize(file.getCanonicalPath()).replaceAll("\\\\", "/");
               String            rootx       = normalize(root.getCanonicalPath());
               String            key;

               if (file.isDirectory())
                  filepath += File.separator;

               if (filepath.startsWith(rootx))
                   key = prefix + filepath.substring(rootx.length() + 1);
               else
                   key = prefix + filepath;

               if (dummyRun) {
                  log(DUMMY_RUN + " Uploading [" + file.getCanonicalPath() + "][" + key + "]");
               } else { 
                   if (verbose) {
                      log("Uploading [" + file.getCanonicalPath() + "][" + key + "]");
                   }

                   upload(service,bucket,acl,cacheNeverExpires,key,file,contentType);
               }
       }
}