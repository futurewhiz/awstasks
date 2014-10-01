package dak.ant.taskdefs;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.MatchingTask;
import org.jets3t.service.Constants;
import org.jets3t.service.acl.AccessControlList;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Bucket;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.utils.ServiceUtils;

/** This class provides basic S3 actions as an Ant task.
  *
  * @author D. Kavanagh
  */
public abstract class AWSTask extends MatchingTask {
       // CONSTANTS

       protected static final String DUMMY_RUN = "<DUMMY RUN>";

       private static final long MAX_AGE = 3 * 60 * 60 * 24 * 30L;

       @SuppressWarnings("serial")
       private static final DateFormat DF = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z")
                                                {{ setTimeZone(TimeZone.getTimeZone("GMT"));
                                                }};

       // INSTANCE VARIABLES

       protected boolean verbose     = false;
       protected boolean failOnError = false;
       protected String  accessId;
       protected String  secretKey;

       // CLASS METHODS

       /** Returns <code>true</code> if an S3Object is most probably a directory i.e. either isDirectoryPlaceHolder
         * is set and the content length is zero or the contentType is "application/x-directory".
         */
       public static boolean isDirectory(S3Object object) {
              // ... directory ?

              if (object.isMetadataComplete() && (object.getContentLength() == 0) && object.isDirectoryPlaceholder()) {
                 return true;
              }

              if ("application/x-directory".equals(object.getContentType())) {
                 return true;
              }

              // ... probably a directory ?
              //
              // if (!object.isMetadataComplete() && (object.getContentLength() == 0) && !object.isDirectoryPlaceholder()) {
              //   return true;
              // }

              return false;
       }

       /** Normalises a Unicode string.
         *
         * Ref. http://stackoverflow.com/questions/3610013
         *
         */
       protected static String normalize(String string) {
                 Normalizer.Form form = Normalizer.Form.NFD;

                 return Normalizer.isNormalized(string, form) ? string : Normalizer.normalize(string, form);
       }

       /** Gracefully closes an I/O stream.
         *
         */
       protected static void close(Closeable stream) {
                 try { if (stream != null)
                          stream.close();
                 } catch (Throwable x) {
                 }
       }

       // PROPERTIES

       /** Sets required task AWS access ID attribute.
         *
         */
       public void setAccessId(String accessId) {
              this.accessId = accessId;
       }

       /** Sets required task AWS secret key attribute.
         *
         */
       public void setSecretKey(String secretKey) {
              this.secretKey = secretKey;
       }

       /** Task attribute to enable/disable verbose logging.
         *
         */
       public void setVerbose(boolean verbose) {
              this.verbose = verbose;
       }

       /** Task attribute to enable/disable failing on error. If <code>false</code> a task
         * will log a warning and attempt to continue uploading/downloading the remaining
         * files.
         *
         */
       public void setFailOnError(boolean failOnError) {
              this.failOnError = failOnError;
       }

       // IMPLEMENTATION

       /** Check that the AWS credentials have been set.
         *
         * @since Ant 1.5
         * @exception BuildException if an error occurs
         */
       protected void checkParameters() throws BuildException {
                 if (accessId == null)
                    throw new BuildException("accessId must be set");

                 if (secretKey == null)
                    throw new BuildException("secretKey must be set");
       }

       /** Replaces jets3t FileComparer buildFileMap to accommodate Ant filesets.
         *
         * @param root Root directory to synchronise.
         * @param files List of files to synchronize.
         * @throws IOException Thrown if the file system fails on File.getCanonicalPath.
         */
       protected Map<String,File> buildFileMap(File root,File[] files,String prefix) throws IOException {
                 Map<String,File> fileMap = new HashMap<String, File>();
                 String          _root    = normalize(root.getCanonicalPath());

                 for (File file: files) {
                     if (file.exists()) {
                        String filepath = normalize(file.getCanonicalPath()).replaceAll("\\\\", "/");

                        if (file.isDirectory()) {
                           filepath += File.separator;
                        }

                        if (filepath.startsWith(_root)) {
                           if (prefix == null)
                              fileMap.put(filepath.substring(_root.length() + 1),file);
                           else
                               fileMap.put(prefix + filepath.substring(_root.length() + 1),file);
                        }
                        else {
                            if (prefix == null)
                                fileMap.put(filepath, file);
                            else
                                fileMap.put(prefix + filepath, file);
                        }
                     }
                 }

                 return fileMap;
       }

       /** Alternative implementation of buildFileMap that accepts a list of strings instead of filenames.
         *
         * @param root  Root directory to synchronise.
         * @param files List of files to synchronize.
         *
         * @throws IOException Thrown if the file system fails on File.getCanonicalPath.
         */
       protected Map<String,File> buildFileMap(File root,String[] files,String prefix) throws IOException  {
                 File[] list = new File[files.length];
                 int    ix   = 0;

                 for (String string: files) {
                     list[ix++] = new File(root, string);
                 }

                 return buildFileMap(root,list,prefix);
       }

       /** Uploads a file to an S3 bucket, setting the ACL to the same as the bucket ACL.
         *
         * @param s3          Initialised S3Service.
         * @param bucket      Initialised S3Bucket.
         * @param key         S3 object key for uploaded file.
         * @param file        Local file to upload.
         * @param contentType MIME type for content.
         *
         * @throws Exception Thrown if the file upload fails for any reason.
         */
       protected void upload(RestS3Service s3,S3Bucket bucket,AccessControlList acl,boolean cacheNeverExpires,String key,File file,String contentType) throws Exception {
                 S3Object object = new S3Object(bucket,key);

                 if (acl != null) {
                    object.setAcl(acl);
                 }

                 if (cacheNeverExpires) {
                    object.addMetadata("Cache-Control", "public, max-age=" + MAX_AGE);
                 }

                 object.addMetadata     (Constants.METADATA_JETS3T_LOCAL_FILE_DATE,ServiceUtils.formatIso8601Date(new Date(file.lastModified())));
                 object.setContentLength(file.length());
                 object.setContentType  (contentType);

                 if (file.isFile() && file.exists()) {
                    object.setDataInputFile(file);
                    s3.putObject(bucket, object);
                 }
       }

}
