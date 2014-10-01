package dak.ant.taskdefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.types.LogLevel;

import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.utils.FileComparer;
import org.jets3t.service.utils.FileComparerResults;

import dak.ant.types.S3File;
import dak.ant.types.S3FileSet;

/** Wraps the JetS3t download functionality in an Ant task.
  * 
  * @author Tony Seebregts
  */
public class S3Download extends AWSTask  {
       // INSTANCE VARIABLES

       private String          dir;
       private List<S3FileSet> filesets = new ArrayList<S3FileSet>();

       private boolean downloadAll     = false;
       private boolean downloadNew     = false;
       private boolean downloadChanged = false;
       private boolean dummyRun        = false;

       // PROPERTIES

       /** Sets the directory to which to download files.
         * 
         * @param dir Download directory. A BuildException will be thrown by
         *            checkParameters() if this is <code>null</code> or
         *            <code>blank</code>.
         */
       public void setDir(String dir) {
              this.dir = dir;
       }

       /** Accepts a comma delimited set of download options:
         * <ul>
         * <li>all - downloads all objects that match the selection criteria
         * <li>new - downloads any objects that match the selection criteria that do
         *           not already exist in the download directory
         * <li>changed - downloads any objects that match the selection criteria
         *               that have changed compared to the existing existing copy in the download
         *               directory
         * </ul>
         * 
         * @param options Comma separated list of download options. Defaults to 'all'.
         */
       public void setDownload(String options) {
              if (options == null)
                 return;

              String[] tokens = options.split(",");

              for (String token: tokens) {
                  String _token = token.trim();

                  if ("all".equalsIgnoreCase(_token))
                     downloadAll = true;
                  else if ("new".equalsIgnoreCase(_token))
                     downloadNew = true;
                  else if ("changed".equalsIgnoreCase(_token))
                     downloadChanged = true;
              }
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

       /** Checks that the AWS credentials and destination directory have been initialised, and 
         * warns if the file list is empty.
         * 
         * @since Ant 1.5
         * @exception BuildException thrown if an error occurs
         */
       @Override
       protected void checkParameters() throws BuildException {
                 super.checkParameters();

                 if ((dir == null) || dir.matches("\\s*"))
                    throw new BuildException("'dir' attribute must be set");

                 if (filesets.isEmpty()) {
                    log("No filesets - nothing to do!", LogLevel.WARN.getLevel());
                    return;
                 }
       }

       /** Downloads all S3 objects that match the nested S3Filesets to the destination directory. 
         * <p>
         * The destination directory and subdirectories are created automatically if necessary.
         * 
         */
       // TODO: optimise to avoid scanning an S3 bucket twice when downloading new/changed files.
       public void execute() throws BuildException {
              checkParameters();

              AWSCredentials credentials = new AWSCredentials(accessId, secretKey);

              try { RestS3Service service   = new RestS3Service(credentials);
                    File          directory = new File(dir);

                    // ... process file sets

                    try { for (S3FileSet fileset: filesets) {
                              Set<S3File> list = new ConcurrentSkipListSet<S3File>();

                              // ... download all ?

                              if (downloadAll || (!downloadNew && !downloadChanged)) {
                                 Iterator<S3File> ix = fileset.iterator(service); 

                                 while (ix.hasNext()) {
                                       list.add(ix.next());
                                 }
                              } else {
                                 // .... download new/changed

                                  FileComparer               fc      = FileComparer.getInstance();
                                  Map<String,File>           map     = buildFileMap(new File(dir),fileset.getPrefix());
                                  Map<String,StorageObject> _objects = fc.buildObjectMap(service,fileset.getBucket(),"",false,null);
                                  FileComparerResults        rs      = fc.buildDiscrepancyLists(map,_objects, null);

                                  Iterator<S3File> ix = fileset.iterator(service); 

                                  while (ix.hasNext()) {
                                        S3File file = ix.next();

                                        if (downloadNew && rs.onlyOnServerKeys.contains(file.getKey())) {
                                           list.add(file);
                                        }

                                        if (downloadChanged && rs.updatedOnServerKeys.contains(file.getKey())) {
                                           list.add(file);
                                        }
                                  }
                              }

                              log("Downloading " + list.size() + " items to '" + dir + "'");

                              for (S3File file: list) {
                                  fetch(service,file,directory);
                              }
                        }
                    } catch (Exception x) { 
                        if (failOnError)
                           throw x;

                        log("Could not retrieve files from Amazon S3 [" + x.getMessage() + "]", LogLevel.ERR.getLevel());
                    }
              } catch(BuildException x) {
                  throw x;
              }
              catch(Exception x) {
                  throw new BuildException(x);
              }
       }

       /** Utility method to download a single S3 object.
         * 
         * @param service   Initialised S3 service.
         * @param file      S3 object to download.
         * @param dir       Destination 'root' directory.
         * 
         * @throws Exception Thrown if the object could not be downloaded or stored.
         */
       private void fetch(RestS3Service service,S3File file,File dir) throws Exception {
               File _file = new File(dir,file.getKey());

               if (dummyRun) {
                  log(DUMMY_RUN + " Downloading [" + file.getBucket() + "::" + file.getKey() + "][" + file + "]");
                  return;
               }

               if (verbose) {
                  log("Downloading [" + file.getBucket() + "::" + file.getKey() + "][" + file + "]");
               }

               S3Object object = service.getObject(file.getBucket(),file.getKey());

               if ("application/x-directory".equals(object.getContentType())) {
                  _file.mkdirs();
                  return;
               }

               InputStream  in     = null;
               OutputStream out    = null;
               byte[]       buffer = new byte[16384];
               int          N;

               try { _file.getParentFile().mkdirs();

                     in = object.getDataInputStream();
                     out = new FileOutputStream(_file);

                     while ((N = in.read(buffer)) != -1) {
                           out.write(buffer, 0, N);
                     }
               } catch(Exception x) {
                   if (failOnError)
                      throw x;

                   log("Could not retrieve file '" + file.getBucket() + "::" + file.getKey() + "' from Amazon S3 [" + x.getMessage() + "]", LogLevel.ERR.getLevel());
               } finally {
                   close(in);
                   close(out);
               }
       }

       /** Builds a jets3t file map for a directory..
         * 
         * @param dir Download directory.
         * 
         * @throws IOException Thrown if the file system fails on File.getCanonicalPath.
         */
       private Map<String,File> buildFileMap(File dir,String prefix) throws IOException {
               return buildFileMap(dir,scan(dir).toArray(new File[0]),prefix);
       }

       /** Recursively iterates through a directory tree to build a list of files.
         * 
         * @param root Root directory to synchronise.
         * 
         * @throws IOException Thrown if the file system fails on File.getCanonicalPath.
         */
       private List<File> scan(File directory) throws IOException { 
               // ... iterate through directory tree

               List<File> list = new ArrayList<File>();
               File[]     files;

               if ((files = directory.listFiles()) != null) {
                  for (File file: files) {
                      list.add(file);

                      if (file.isDirectory())
                         list.addAll(scan(file));
                  }
               }

               return list;
       }
}
