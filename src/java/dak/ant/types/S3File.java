package dak.ant.types;

import java.io.File;

import org.jets3t.service.model.S3Object;

import dak.ant.taskdefs.AWSTask;

/** Attempt to make S3 objects compatible with ant file selectors.
  * This could probably be done a lot better. One thing to explore would
  * be the URI constructor - this might allow bucket to be used as part
  * of the file path which might make some of the S3 operations easier
  * to handle? Or maybe harder!
  * 
  * It might be here that you could plug in a File System implementation that
  * uses any of a number of naming schemes to simulate directories within S3 -
  * a number of tools do this and it'd be nice to be able to read their
  * output. 
  * 
  * I should probably handle isAbsolute some how.
  * 
  * @author Chris Stewart
  *
  */
@SuppressWarnings("serial")
public class S3File extends File {
     
       // INSTANCE VARIABLES
    
       private String  bucket;
       private String  key;
       private int     hashcode;
       private long    lastModified = 0;
       private long    length       = 0;
       private boolean exists       = false;
       private boolean isDirectory  = true;

       // CLASS METHODS

       /** Returns an empty string if the original string is <code>null</code>.
         * 
         */
       private static String clean(String string) {
               return string == null ? "" : string;
       }

       // CONSTRUCTORS

       /** Default constructor for use by <code>S3FileSet.createS3File</code> <b>only</b>. Initialises the 
         * the <code>bucket</code> and <code>key</code> attributes to <code>blank</code>, expecting them to 
         * be filled in later i.e. be nice to it !
         * 
         * 
         */
       public S3File() { 
              this("","");
       }

       /** Initialises the <code>bucket</code> and <code>key</code> attributes. It does not download the metadata, 
         * length etc from S3 so cannot be used when scanning in selectors.
         *  
         * @param bucket S3 bucket name.
         * @param key    S3 object key.
         */
       public S3File(String bucket,String key) { 
              super(key);

              this.bucket   = clean(bucket);
              this.key      = clean(key);
              this.hashcode = (this.bucket + "/" + this.key).hashCode();
       }

       /** Initialises the <code>bucket</code> and <code>key</code> and other attributes from a real live S3 object for use
         * in selector scanners.
         *  
         * @param object Fully initialised S3Object.
         */
       public S3File(S3Object object) { 
              super(object.getKey());

              this.bucket       = clean(object.getBucketName());
              this.key          = clean(object.getKey());
              this.hashcode     = (this.bucket + "/" + this.key).hashCode();
              this.lastModified = object.getLastModifiedDate().getTime();
              this.length       = object.getContentLength();
              this.exists       = true;
              this.isDirectory  = AWSTask.isDirectory(object);
       }

       // PROPERTIES

       /** Sets the <code>bucket</code> attribute.
         *
         * @param bucket S3 bucket name. Stored as "" if <code>null</code>.
         */
       public void setBucket(String bucket) {
              this.bucket   = clean(bucket);
              this.hashcode = (this.bucket + "/" + this.key).hashCode();
       }

       /** Returns the current <code>bucket</code> attribute value.
         *
         * @return S3 bucket name. May be blank, will not be <code>null</code>.
         */
       public String getBucket() { 
              return bucket;
       }

       /** Sets the <code>key</code> attribute.
         *
         * @param key S3 object name. Stored as "" if <code>null</code>.
         */
       public void setKey(String key) { 
              this.key      = clean(key);
              this.hashcode = (this.bucket + "/" + this.key).hashCode();
       }

       /** Returns the current <code>key</code> attribute value.
         *
         * @return S3 object key. May be blank, will not be <code>null</code>.
         */
       public String getKey() {
              return key;
       }

       // *** File ***

       /** Returns <code>true</code> if the S3File represents an S3 folder.
         * <p>
         * Only valid if this S3File instance was initialised from a fully initialised S3Object in the constructor, 
         * and the original directory was uploaded with either isDirectoryPlaceHolder set and/or a contentType of 
         * application/x-directory (which is often not the case).
         *  
         */
       @Override
       public boolean isDirectory() { 
              return isDirectory; 
       }

//     /** Sets the 'last modified' time of this S3File, without updating the 'wrapped' S3Object.
//       * 
//       * @param time 'last modified' time.
//       *  
//       */
//     @Override
//     public boolean setLastModified(long time) { 
//            lastModified = time;
//                 
//            return true;
//     }
   
       /** Returns the 'last modified' time of the 'wrapped' S3Object.
         * <p>
         * Only valid if this S3File instance was initialised from a fully initialised S3Object in the constructor.
         *  
         */
       @Override
       public long lastModified() { 
              return lastModified;
       }

       /** Returns the size of the 'wrapped' S3Object.
         * <p>
         * Only valid if this S3File instance was initialised from a fully initialised S3Object in the constructor.
         *  
         */
       @Override
       public long length() { 
              return length;
       }
         
       /** Returns the <code>true</code> if this S3File instance was initialised from a fully initialised S3Object 
         * in the constructor.
         *  
         */
       @Override
       public boolean exists() { 
              return exists;
       }

       // *** Object ***

       /** Compares equal if the bucket and key are the same.
         *  
         */
       @Override
       public boolean equals(Object object) { 
              if (object instanceof S3File)
                 if (bucket.equals(((S3File) object).bucket))
                    if (key.equals(((S3File) object).key))
                       return true;

              return false;
       }

       /** Returns a hashcode derived from the bucket and key attributes.
         * 
         */
       @Override
       public int hashCode() {
           return hashcode;
       }
}
