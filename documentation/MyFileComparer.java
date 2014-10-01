package dak.ant.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jets3t.service.Constants;
import org.jets3t.service.Jets3tProperties;
import org.jets3t.service.io.BytesProgressWatcher;
import org.jets3t.service.io.ProgressMonitoredInputStream;
import org.jets3t.service.model.StorageObject;
import org.jets3t.service.utils.FileComparer;
import org.jets3t.service.utils.FileComparerResults;
import org.jets3t.service.utils.ServiceUtils;

public class MyFileComparer extends FileComparer
       { // CLASS VARIABLES
    
         private static final Log log = LogFactory.getLog(FileComparer.class);
         
         // INSTANCE VARIABLES
    
         private Jets3tProperties jets3tProperties = null;

         // CONSTRUCTORS

         public MyFileComparer() 
                { super(Jets3tProperties.getInstance(Constants.JETS3T_PROPERTIES_FILENAME));
                
                  jets3tProperties = Jets3tProperties.getInstance(Constants.JETS3T_PROPERTIES_FILENAME);
                }
         
         // IMPLEMENTATION
         
         public Map<String, File> buildFileMap(File root,File[] files, boolean includeDirectories) throws IOException 
                { Map<String,File> fileMap                        = new HashMap<String, File>();
                  List<Pattern>    ignorePatternList              = null;
                  List<Pattern>    ignorePatternListForCurrentDir = null;
                  String           rootx                          = normalizeUnicode(root.getCanonicalPath());

                  for (int i = 0; i < files.length; i++) 
                      { File file = files[i];

                        if (file.getParentFile() == null) 
                           { if (ignorePatternListForCurrentDir == null) 
                                { ignorePatternListForCurrentDir = buildIgnoreRegexpList(new File("."), null);
                                }
                     
                             ignorePatternList = ignorePatternListForCurrentDir;
                           } 
                           else 
                           { ignorePatternList = buildIgnoreRegexpList(file.getParentFile(), null);
                           }

                        if (!isIgnored(ignorePatternList, file)) 
                           { if (!file.exists()) 
                                { continue;
                                }
                     
                             if (!file.isDirectory()) 
                                { String filepath = normalizeUnicode(file.getCanonicalPath());
                                 
                                  if (filepath.startsWith(rootx))
                                     fileMap.put(filepath.substring(rootx.length() + 1), file);
                                     else
                                     fileMap.put(filepath, file);
                                }
                     
                             if (file.isDirectory() && includeDirectories) 
                                { String filepath = normalizeUnicode(file.getCanonicalPath()) + File.separator;
                                 
                                  if (filepath.startsWith(rootx))
                                     fileMap.put(filepath.substring(rootx.length() + 1), file);
                                     else
                                     fileMap.put(filepath, file);
                                }
                           }
                      }
             
                  return fileMap;
                }         

         @Override
         public FileComparerResults buildDiscrepancyLists(Map<String,File>          filesMap,
                                                          Map<String,StorageObject> objectsMap, 
                                                          BytesProgressWatcher      progressWatcher) throws NoSuchAlgorithmException, FileNotFoundException, IOException, ParseException
                { System.err.println("WHOAA - HERE WE GO ....");
                  Set<String> onlyOnServerKeys              = new HashSet<String>();
                  Set<String> updatedOnServerKeys           = new HashSet<String>();
                  Set<String> updatedOnClientKeys           = new HashSet<String>();
                  Set<String> onlyOnClientKeys              = new HashSet<String>();
                  Set<String> alreadySynchronisedKeys       = new HashSet<String>();
                  Set<String> alreadySynchronisedLocalPaths = new HashSet<String>();

                  // Read property settings for file comparison.
                 
                  boolean useMd5Files                 = jets3tProperties.getBoolProperty("filecomparer.use-md5-files",     false);
                  boolean generateMd5Files            = jets3tProperties.getBoolProperty("filecomparer.generate-md5-files",false);
                  boolean assumeLocalLatestInMismatch = jets3tProperties.getBoolProperty("filecomparer.assume-local-latest-in-mismatch", false);
                  String  md5FilesRootDirectoryPath   = jets3tProperties.getStringProperty("filecomparer.md5-files-root-dir", null);
                  File    md5FilesRootDirectory       = null;
                 
                  if (md5FilesRootDirectoryPath != null) 
                     { md5FilesRootDirectory = new File(md5FilesRootDirectoryPath);
                     
                       if (!md5FilesRootDirectory.isDirectory()) 
                          { throw new FileNotFoundException("filecomparer.md5-files-root-dir path is not a directory: " + md5FilesRootDirectoryPath);
                          }
                     }

                  // Check files on server against local client files.
                
                  Iterator<Map.Entry<String, StorageObject>> objectsMapIter = objectsMap.entrySet().iterator();
              
                  while (objectsMapIter.hasNext()) 
                        { Map.Entry<String, StorageObject> entry = objectsMapIter.next();
                          String        keyPath       = entry.getKey();
                          StorageObject storageObject = entry.getValue();

                          for (String localPath: splitFilePathIntoDirPaths(keyPath, storageObject.isDirectoryPlaceholder()))
                              { // Check whether local file is already on server
                          
                                System.err.println("LOCAL PATH: " + localPath);
                                
                                for (String key: filesMap.keySet())
                                    { System.err.println(">>> KEY: [" + key + "]");
                                    }
                                
                                if (filesMap.containsKey(localPath)) 
                                   { // File has been backed up in the past, is it still up-to-date?
                                     
                                     File file = filesMap.get(localPath);

                                     if (file.isDirectory()) 
                                        { // We don't care about directory date changes, as long as it's present.
                                  
                                          alreadySynchronisedKeys.add(keyPath);
                                          alreadySynchronisedLocalPaths.add(localPath);
                                        } 
                                        else
                                        { // Compare file hashes.
                                          
                                          byte[] computedHash = null;

                                          // Check whether a pre-computed MD5 hash file is available
                                 
                                          File computedHashFile = (md5FilesRootDirectory != null ? new File(md5FilesRootDirectory, localPath + ".md5") : new File(file.getPath() + ".md5"));
                                 
                                          if (useMd5Files && computedHashFile.canRead() && computedHashFile.lastModified() > file.lastModified())
                                             { BufferedReader br = null;
                                             
                                               try 
                                                  { // A pre-computed MD5 hash file is available, try to read this hash value
                                          
                                                    br           = new BufferedReader(new FileReader(computedHashFile));
                                                    computedHash = ServiceUtils.fromHex(br.readLine().split("\\s")[0]);
                                                  }
                                               catch (Exception e) 
                                                  { if (log.isWarnEnabled()) 
                                                      { log.warn("Unable to read hash from computed MD5 file", e);
                                                      }
                                                  } 
                                               finally 
                                                  { if (br != null) 
                                                      { br.close();
                                                      }
                                                  }
                                             }

                                          if (computedHash == null) 
                                             { // A pre-computed hash file was not available, or could not be read.
                                               // Calculate the hash value anew.
                                     
                                               InputStream hashInputStream = null;
                                     
                                               if (progressWatcher != null) 
                                                  { hashInputStream = new ProgressMonitoredInputStream(new FileInputStream(file), progressWatcher);
                                                  } 
                                                  else
                                                  { hashInputStream = new FileInputStream(file);
                                                  }
                                     
                                               computedHash = ServiceUtils.computeMD5Hash(hashInputStream);
                                             }

                                          String fileHashAsBase64 = ServiceUtils.toBase64(computedHash);

                                          if (generateMd5Files && !file.getName().endsWith(".md5") && (!computedHashFile.exists() || computedHashFile.lastModified() < file.lastModified()))
                                             { // Create parent directory for new hash file if necessary
                                              
                                               File parentDir = computedHashFile.getParentFile();
                                     
                                               if (parentDir != null && !parentDir.exists()) 
                                                  { parentDir.mkdirs();
                                                  }

                                               // Create or update a pre-computed MD5 hash file.
                                     
                                               FileWriter fw = null;
                                               
                                               try 
                                                  { fw = new FileWriter(computedHashFile);
                                                    fw.write(ServiceUtils.toHex(computedHash));
                                                  } 
                                               catch (Exception e) 
                                                 { if (log.isWarnEnabled()) 
                                                     { log.warn("Unable to write computed MD5 hash to a file", e);
                                                     }
                                                 }
                                               finally 
                                                 { if (fw != null) 
                                                      { fw.close();
                                                      }
                                                 }
                                             }

                                          // Get the service object's Base64 hash.
                                 
                                          String objectHash = null;
                                          
                                          if (storageObject.containsMetadata(StorageObject.METADATA_HEADER_ORIGINAL_HASH_MD5)) 
                                             { // Use the object's *original* hash, as it is an encoded version of a local file.
                                              
                                               objectHash = (String) storageObject.getMetadata(StorageObject.METADATA_HEADER_ORIGINAL_HASH_MD5);
                                     
                                               if (log.isDebugEnabled()) 
                                                  { log.debug("Object in service is encoded, using the object's original hash value for: " + storageObject.getKey());
                                                  }
                                             }
                                             else
                                             { // The object wasn't altered when uploaded, so use its current hash.
                                     
                                               objectHash = storageObject.getMd5HashAsBase64();
                                             }

                                          if (fileHashAsBase64.equals(objectHash)) 
                                             { // Hashes match so file is already synchronised.
                                              
                                               alreadySynchronisedKeys.add(keyPath);
                                               alreadySynchronisedLocalPaths.add(localPath);
                                             }
                                            else
                                            { // File is out-of-synch. Check which version has the latest date.
                                     
                                              Date objectLastModified = null;
                                              String metadataLocalFileDate = (String) storageObject.getMetadata(Constants.METADATA_JETS3T_LOCAL_FILE_DATE);

                                              if (metadataLocalFileDate == null) 
                                                 { // This is risky as local file times and service times don't match!
                                         
                                                   if (!assumeLocalLatestInMismatch && log.isWarnEnabled()) 
                                                      { log.warn("Using service last modified date as file date. This is not reliable as the time according to service can differ from your local system time. Please use the metadata item " + Constants.METADATA_JETS3T_LOCAL_FILE_DATE);
                                                      }
                                         
                                                   objectLastModified = storageObject.getLastModifiedDate();
                                                 } 
                                                 else
                                                 { objectLastModified = ServiceUtils.parseIso8601Date(metadataLocalFileDate);
                                                 }
                                     
                                              if (objectLastModified.getTime() > file.lastModified()) 
                                                 { updatedOnServerKeys.add(keyPath);
                                                 }
                                                 else if (objectLastModified.getTime() < file.lastModified()) 
                                                 { updatedOnClientKeys.add(keyPath);
                                                 } 
                                                 else 
                                                 { // Local file date and service object date values match exactly, yet the
                                                   // local file has a different hash. This shouldn't ever happen, but
                                                   // sometimes does with Excel files.
                                         
                                                   if (assumeLocalLatestInMismatch) 
                                                      { if (log.isWarnEnabled()) 
                                                           { log.warn("Backed-up object " + storageObject.getKey() + " and local file " + file.getName() + " have the same date but different hash values. " + "Assuming local file is the latest version.");
                                                           }
                                             
                                                        updatedOnClientKeys.add(keyPath);
                                                      }
                                                      else
                                                      { throw new IOException("Backed-up object " + storageObject.getKey() + " and local file " + file.getName() + " have the same date but different hash values. " + "This shouldn't happen!");
                                                      }
                                                 }
                                            }
                                        }
                                   } 
                                   else
                                   { // File is not in local file system, so it's only on the service.
                             
                                     System.err.println("----- [" + keyPath + "]");

                                     onlyOnServerKeys.add(keyPath);
                                   }
                              }
                        }

                 // Any local files not already put into another list only exist locally.
                  
                 onlyOnClientKeys.addAll(filesMap.keySet());
                 onlyOnClientKeys.removeAll(updatedOnClientKeys);
                 onlyOnClientKeys.removeAll(updatedOnServerKeys);
                 onlyOnClientKeys.removeAll(alreadySynchronisedKeys);
                 onlyOnClientKeys.removeAll(alreadySynchronisedLocalPaths);

                 return new FileComparerResults(onlyOnServerKeys, updatedOnServerKeys, updatedOnClientKeys,
                     onlyOnClientKeys, alreadySynchronisedKeys, alreadySynchronisedLocalPaths);
             }
         
         private Set<String> splitFilePathIntoDirPaths(String path, boolean isDirectoryPlaceholder) {
             Set<String> dirPathsSet = new HashSet<String>();
             String[] pathComponents = path.split(Constants.FILE_PATH_DELIM);
             String myPath = "";
             for (int i = 0; i < pathComponents.length; i++) {
                 String pathComponent = pathComponents[i];
                 myPath = myPath + pathComponent;
                 if (i < pathComponents.length - 1 || isDirectoryPlaceholder) {
                     myPath += Constants.FILE_PATH_DELIM;
                 }
                 dirPathsSet.add(myPath);
             }
             return dirPathsSet;
         }

       }