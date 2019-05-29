package dak.ant.taskdefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

import javax.activation.MimetypesFileTypeMap;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.LogLevel;

import org.jets3t.service.Constants;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.acl.GroupGrantee;
import org.jets3t.service.acl.Permission;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.utils.FileComparer;
import org.jets3t.service.utils.FileComparerResults;

/** Implements an Ant task with the JetS3t synchronise functionality.
  * 
  * @author Tony Seebregts
  */
public class S3Synchronize extends AWSTask {
       // CONSTANTS

       private enum DIRECTION { 
               UPLOAD("upload"), 
               DOWNLOAD("download");
               
               private final String code;

               private DIRECTION(String code) {
                       this.code = code;
               }

               private static DIRECTION parse(String code) {
                       for (DIRECTION direction: values()) {
                           if (direction.code.equalsIgnoreCase(code))
                              return direction;
                       }

                       return null;
               }
       };

       // INSTANCE VARIABLES

       private String               bucket;
       private String               prefix            = "";
       private boolean              publicRead        = false;
       private List<FileSet>        filesets          = new ArrayList<FileSet>();
       private boolean              cacheNeverExpires = false;
       private MimetypesFileTypeMap mimeTypesMap      = new MimetypesFileTypeMap();
       private String               mimeTypesFile;
       private AccessControlList    acl;

       private DIRECTION direction;
       private boolean   dummyRun = false;
       private boolean   delete   = false;
       private boolean   revert   = false;

       // PROPERTIES

       /** Required task attribute - sets the S3 bucket with which to synchronize 
         * a local folder.
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

       /** Sets the synchronisation direction.
         * 
         * @param direction "upload" or "download".
         */
       public void setDirection(String direction) {
              this.direction = DIRECTION.parse(direction);
       }

       /** Deletes files in the destination that do not have an equivalent source object if <code>true</code>. 
         * Default value is <code>false</code>.
         * 
         * @param enabled If <code>false</code>, does not delete files that would otherwise be deleted.
         */
       public void setDelete(boolean enabled) {
              this.delete = enabled;
       }

       /** Overwrites files in the destination that are newer than the equivalent source object if <code>true</code>. 
         * Default value is <code>false</code>.
         * 
         * @param enabled If <code>false</code>, does not overwrite files that would otherwise be overwritten.
         */
       public void setRevert(boolean enabled) {
              this.revert = enabled;
       }

//     /** Sets the include pattern for the local directory.
//       * 
//       * @param enabled
//       */
//     public void setIncludeDirectories(boolean enabled) {
//             this.includeDirectories = enabled;
//     }

       /** Create method for nested Ant filesets that specify the local directory for synchronisation.
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

       /** Task attribute to execute the synchronize  as a 'dummy run' to verify that it will do 
         * what is intended. 
         * 
         */
       public void setDummyRun(boolean enabled) {
              this.dummyRun = enabled;
       }

       // IMPLEMENTATION

       /** Check that all required attributes have been set and warns if the fileset
         * list is empty.
         * 
         * @exception BuildException if an error occurs
         */
       @Override
       protected void checkParameters() throws BuildException {
                 super.checkParameters();

                 if (direction == null) {
                    throw new BuildException("Invalid 'synchronize' direction. Valid values are 'upload' or 'download'");
                 }

                 if (filesets == null) {
                    log("No fileset specified, doing nothing", LogLevel.WARN.getLevel());
                    return;
                 }
       }

       /** Validates the parameters and then synchronizes the S3 and file specified
        * in the filesets.
        * 
        * @throws BuildException Thrown if the parameters are invalid or the synchronize failed.
        */
       @Override
       public void execute() throws BuildException  {
              checkParameters();

              try { // ... initialise

                    AWSCredentials credentials = new AWSCredentials(accessId, secretKey);
                    RestS3Service  s3          = new RestS3Service(credentials);
                    S3Bucket       bucket      = new S3Bucket(this.bucket);

                    if (publicRead) {
                       acl = s3.getBucketAcl(bucket);
                       acl.grantPermission(GroupGrantee.ALL_USERS,Permission.PERMISSION_READ);
                    }

                    if (mimeTypesFile != null)
                        mimeTypesMap = new MimetypesFileTypeMap(mimeTypesFile);
                    else 
                        mimeTypesMap = new MimetypesFileTypeMap();

                    // ... synchronise directory

                    try { for (FileSet fs : filesets) {
                              DirectoryScanner ds      = fs.getDirectoryScanner(getProject());
                              File             root    = fs.getDir(getProject());
                              String[]         subdirs = ds.getIncludedDirectories();
                              String[]         files   = ds.getIncludedFiles();
                              List<File>       list    = new ArrayList<File>();

                              for (String dir: subdirs) {
                                  list.add(new File(root, dir));
                              }

                              for (String file: files) {
                                  list.add(new File(root, file));
                              }

                              switch (direction) {
                                     case UPLOAD:
                                          upload(s3,bucket,root,list.toArray(new File[0]));
                                          break;

                                     case DOWNLOAD:
                                          download(s3,bucket,root,list.toArray(new File[0]));
                                          break;
                              }
                        }
                    } catch (BuildException x) {
                        if (failOnError)
                           throw x;

                        log("Error synchronizing files with Amazon S3 [" + x.getMessage() + "]", LogLevel.ERR.getLevel());
                    }
              } catch (BuildException x) {
                  throw x;
              } catch (Exception x) {
                  throw new BuildException(x);
              }
       }

       /** Utility method to upload a list of files from a directory.
         * 
         * @param service  Initialise S3 service.
         * @param bucket   Destination bucket. Created automatically if required.
         * @param root     'root' directory for file list. Used to match against S3 object list.
         * @param list     List of files to upload.
         * 
         * @throws Exception Thrown if a file in the list could not be uploaded and 'failOnError' is set.
         */
       private void upload(RestS3Service service,S3Bucket bucket,File root,File[] list) throws Exception {
               // ... build change list

               FileComparer              fc      = FileComparer.getInstance();
               Map<String,File>          files   = buildFileMap     (root,list,prefix);
               Map<String,StorageObject> objects = fc.buildObjectMap(service,bucket.getName(),"",false,null);
               FileComparerResults       rs      = fc.buildDiscrepancyLists(files, objects);
               AccessControlList         acl     = publicRead ? this.acl : null;

               // ... synchronize

               for (String key: rs.onlyOnClientKeys) {
                   File   file        = files.get(key);
                   String contentType = mimeTypesMap.getContentType(file);

                   if (file.isDirectory())
                       continue;

                   if (dummyRun)
                       log(DUMMY_RUN + " Added: [" + key + "]");
                   else {
                       if (verbose)
                          log("Added: " + "[" + key + "][" + file + "]");

                       upload(service,bucket,acl,cacheNeverExpires,key,file,contentType);
                   }
               }

               for (String key: rs.updatedOnClientKeys) {
                   File   file        = files.get(key);
                   String contentType = mimeTypesMap.getContentType(file);

                   if (file.isDirectory())
                      continue;

                   if (dummyRun)
                      log(DUMMY_RUN + " Updated: [" + key + "]");
                   else  {
                       if (verbose)
                          log("Updated: " + "[" + key + "][" + file + "]");

                       upload(service,bucket,acl,cacheNeverExpires,key,file,contentType);
                   }
               }

               for (String key: rs.onlyOnServerKeys) {
                   if (!key.startsWith(prefix))
                   {
                       continue;
                   }

                   if (delete) {
                      if (dummyRun)
                         log(DUMMY_RUN + " Deleted: [" + key + "]");
                      else 
                          delete(service,bucket,key,"Deleted: ");
                   }
               }

               for (String key: rs.updatedOnServerKeys) {
                   File   file        = files.get(key);
                   String contentType = mimeTypesMap.getContentType(file);

                   if (revert) {
                      if (dummyRun)
                         log(DUMMY_RUN + " Reverted: [" + key + "]");
                      else {
                          if (verbose)
                             log("Reverted: " + "[" + key + "][" + file + "]");

                          upload(service,bucket,acl,cacheNeverExpires,key,file,contentType);
                      }
                   }
               }
       }

       /** Utility method to download a list of files to a directory.
         * 
         * @param service  Initialises S3 service.
         * @param bucket   Source bucket.
         * @param root     'root' directory for file list. Used to match against S3 object list.
         * @param list     List of files to download.
         * 
         * @throws Exception Thrown if a file in the list could not be uploaded and 'failOnError' is set.
         */
       private void download(RestS3Service service,S3Bucket bucket,File root,File[] list) throws Exception {
               // ... build change list

               FileComparer              fc      = FileComparer.getInstance();
               Map<String,File>          files   = buildFileMap     (root,list,prefix);
               Map<String,StorageObject> objects = fc.buildObjectMap(service,bucket.getName(),"",false,null);
               FileComparerResults       rs      = fc.buildDiscrepancyLists(files, objects);

               // ... synchronize

               for (String key: rs.onlyOnServerKeys) {
                   if (!key.startsWith(prefix))
                   {
                       continue;
                   }

                   if (dummyRun)
                      log(DUMMY_RUN + " Added: [" + key + "]");
                   else
                       download(service,bucket,key,new File(root,key), "Added:");
               }

               for (String key: rs.updatedOnServerKeys) {
                   if (dummyRun)
                       log(DUMMY_RUN + " Updated: [" + key + "]");
                   else
                       download(service,bucket,key,files.get(key),"Updated: ");
               }

               for (String key: rs.onlyOnClientKeys) {
                   if (delete) {
                      if (dummyRun)
                          log(DUMMY_RUN + " Deleted: [" + key + "]");
                      else
                          delete(files.get(key),"Deleted: ");
                   }
               }

               for (String key: rs.updatedOnClientKeys) {
                   if (revert) {
                      if (dummyRun)
                          log(DUMMY_RUN + " Reverted: [" + key + "]");
                      else
                          download(service, bucket, key, files.get(key), "Reverted: ");
                   }
               }
       }

       /** Downloads a file from an S3 bucket.
         * 
         * @param s3     Initialised S3Service.
         * @param key    S3 object key for file to download.
         * @param file  Local file to which to download.
         * @param action Action text for log message.
         * 
         * @throws Exception Thrown if the file upload fails for any reason.
         */
       private void download(RestS3Service s3,S3Bucket bucket, String key,File file,String action) throws Exception {
               if (verbose) {
                  log(action + "[" + key + "][" + file + "]");
               }

               // ... get object

               S3Object object = s3.getObject(bucket.getName(), key);

               // ... directory ?

               if (!object.isMetadataComplete() && (object.getContentLength() == 0) && !object.isDirectoryPlaceholder()) {
                  log("Skipping '" + key + "' - may or may not be a directory");
                  return;
               }

               if (object.isMetadataComplete() && (object.getContentLength() == 0) && object.isDirectoryPlaceholder()) {
                  log("Creating directory '" + key + "'");
                  file.mkdirs();
                  return;
               }

               if (file.exists() && file.isDirectory()) {
                  log("Warning: file '" + key + "' exists as a directory");
                  return;
               }

               // ... download file

               byte[]       buffer = new byte[16384];
               InputStream  in     = null;
               OutputStream out    = null;
               int          N;

               try { file.getParentFile().mkdirs();

                     in = object.getDataInputStream();
                     out = new FileOutputStream(file);

                     while ((N = in.read(buffer)) != -1) {
                           out.write(buffer,0,N);
                     }
               } finally {
                   close(in);
                   close(out);
               }
       }

       /** Deletes a file from an S3 bucket.
         * 
         * @param s3     Initialised S3Service.
         * @param bucket Initialised S3Bucket.
         * @param key    S3 object key to delete.
         * @param action Action text for log message.
         * 
         * @throws Exception
         *             Thrown if the file upload fails for any reason.
         */
       private void delete(RestS3Service s3,S3Bucket bucket,String key,String action) throws Exception {
               if (verbose) { 
                  log(action + "[" + key + "]");
               }

               s3.deleteObject(bucket, key);
       }

       /** Deletes a local file.
         * 
         * @param file   Local file to delete.
         * @param action Action text for log message.
         * 
         * @throws Exception Thrown if the file upload fails for any reason.
         */
       private void delete(File file,String action) throws Exception {
               if (verbose) {
                  log(action + "[" + file + "]");
               }

               if (file.exists())
                   file.delete();
       }
}
