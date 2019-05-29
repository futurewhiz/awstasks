package dak.ant.types;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.DataType;
import org.apache.tools.ant.types.PatternSet;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.types.selectors.AndSelector;
import org.apache.tools.ant.types.selectors.DateSelector;
import org.apache.tools.ant.types.selectors.DependSelector;
import org.apache.tools.ant.types.selectors.DepthSelector;
import org.apache.tools.ant.types.selectors.ExtendSelector;
import org.apache.tools.ant.types.selectors.FileSelector;
import org.apache.tools.ant.types.selectors.FilenameSelector;
import org.apache.tools.ant.types.selectors.MajoritySelector;
import org.apache.tools.ant.types.selectors.NoneSelector;
import org.apache.tools.ant.types.selectors.NotSelector;
import org.apache.tools.ant.types.selectors.OrSelector;
import org.apache.tools.ant.types.selectors.PresentSelector;
import org.apache.tools.ant.types.selectors.SelectSelector;
import org.apache.tools.ant.types.selectors.SelectorUtils;
import org.apache.tools.ant.types.selectors.SizeSelector;
import org.jets3t.service.S3Service;
import org.jets3t.service.model.S3Object;

import dak.ant.selectors.S3KeySelector;

/** Ant fileset look-alike for S3 object sets. 
  * <p>
  * Based on Chris Stewart's original implementation but with the ResourceCollection and SelectorContainer 
  * interfaces removed as being too liberal with the current S3File implementation.
  * <p>
  * Supports:
  * <ul>
  * <li> includes/excludes
  * <li> nested patternset's
  * <li> nested S3File's
  * <li> and the following Ant selectors:
  *      <ul>
  *      <li> filename
  *      <li> S3Key
  *      <li> date
  *      <li> and
  *      <li> or
  *      <li> not
  *      <li> none
  *      <li> select
  *      <li> majority
  *      <li> present
  *      <li> size
  *      <li> depth
  *      <li> depend
  *      <li> custom (use with caution)
  *      </ul>
  * </ul>
  * 
  * @author Tony Seebregts
  *
  */
public class S3FileSet extends DataType {
    
       // INSTANCE VARIABLES
    
       private String             bucket;
       private String             prefix;
       private List<S3File>       files                = new ArrayList<S3File>();
       private PatternSet         defaultPatterns      = new PatternSet();
       private List<PatternSet>   additionalPatterns   = new ArrayList<PatternSet>  ();
       private List<FileSelector> selectors            = new ArrayList<FileSelector>();
       private List<S3File>       included;

       // TASK ATTRIBUTES

       /** Sets the S3 bucket for this fileset and any nested S3File selectors.
         * 
         * @param bucket S3 bucket name.
         */
       public void setBucket(String bucket) { 
              if (isReference())
                 throw tooManyAttributes();

              this.bucket   = bucket;
              this.included = null;

              for (S3File file: files)
                  file.setBucket(bucket);
       }

       /** Returns the S3 bucket attribute, dereferencing it if required.
         * 
         * @return S3 bucket name.
         */
       public String getBucket() { 
              if (isReference())
                 return getRef(getProject()).getBucket();

              dieOnCircularReference();

              return bucket;
       }
         
       /** Sets the prefix to use inside an S3 bucket.
         * 
         * @param prefix  Prefix for folder in S3 bucket. Default value is "" (none).
         */
       public void setPrefix(String prefix) { 
              if (isReference())
                 throw tooManyAttributes();

              this.prefix   = prefix;
              this.included = null;
       }

       /** Returns the S3 prefix attribute, dereferencing it if required.
        * 
        */
       public String getPrefix() {
              if (isReference())
                 return getRef(getProject()).getPrefix();

              dieOnCircularReference();

              return prefix;
       }

       // PATTERN ATTRIBUTES

       /**  Creates a nested &lt;patternset&gt;.
         * 
         * @return <code>PatternSet</code>.
         */
       public synchronized PatternSet createPatternSet() {
              if (isReference())
                 throw noChildrenAllowed();

              PatternSet patterns = new PatternSet();

              additionalPatterns.add(patterns);

              this.included = null;

              return patterns;
       }

       /** Appends <code>includes</code> to the current list of include patterns.
         * <p>
         * Patterns may be separated by a comma or a space.
         * </p>
         * 
         * @param includes the <code>String</code> containing the include patterns.
         */
       public synchronized void setIncludes(String includes) { 
              if (isReference())
                 throw tooManyAttributes();

              this.defaultPatterns.setIncludes(includes);
              this.included = null;
       }

       /** Appends <code>excludes</code> to the current list of exclude patterns.
         * <p>
         * Patterns may be separated by a comma or a space.
         * </p>
         * 
         * @param excludes the <code>String</code> containing the exclude patterns.
         */
       public synchronized void setExcludes(String excludes) { 
              if (isReference())
                 throw tooManyAttributes();

              this.defaultPatterns.setExcludes(excludes);
              this.included = null;
       }

       /** create&lt;Type&gt; implementation for an included <code>S3File</code>.
         * 
         */
       public S3File createS3File() { 
              if (isReference())
                 throw noChildrenAllowed();

              S3File file = new S3File();

              file.setBucket(this.bucket);
              files.add     (file);

              this.included = null;

              return file;
       }
    
       /** Utility attribute for antlib definitions. create&lt;Type&gt; implementation for an included <code>s3:File</code>.
         * 
         */
       public S3File createFile() { 
              if (isReference())
                  throw noChildrenAllowed();

              S3File file = new S3File();

              file.setBucket(this.bucket);
              files.add     (file);

              this.included = null;

              return file;
       }
   
       // TESTED SELECTORS

       /** Adds a nested &lt;filename&gt; selector.
         * 
         */
       public void addFilename(FilenameSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;S3Key&gt; selector.
         * 
         */
       public void addKey(S3KeySelector selector) {
              appendSelector(selector);
           }

       /** Adds a nested &lt;date&gt; selector.
         * 
         */
       public void addDate(DateSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;and&gt; selector.
         * 
         */
       public void addAnd(AndSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;or&gt; selector.
        * 
        */
       public void addOr(OrSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;not&gt; selector.
         * 
         */
       public void addNot(NotSelector selector) { 
              appendSelector(selector);
       }

       /** Adds a nested &lt;none&gt; selector.
         * 
         */
       public void addNone(NoneSelector selector) { 
              appendSelector(selector);
       }

       /** Adds a nested &lt;select&gt; selector.
         * 
         */
       public void addSelector(SelectSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;majority&gt; selector.
         * 
         */
       public void addMajority(MajoritySelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;present&gt; selector.
        * 
        */
       public void addPresent(PresentSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;size&gt; selector.
         * 
         */
       public void addSize(SizeSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;depth&gt; selector.
         * 
         */
       public void addDepth(DepthSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;depend&gt; selector.
         * 
         */
       public void addDepend(DependSelector selector) {
              appendSelector(selector);
       }

       /** Adds a nested &lt;custom&gt; selector.
         * <p>
         * Use with caution: an S3Object is not a 'proper' file.
         * 
         * @param selector ExtendSelector implementation.
         */
       public void addCustom(ExtendSelector selector) {
              appendSelector(selector);
       }

       /** Adds a FileSelector implementation the internal selector list..
         * 
         */
       private synchronized void appendSelector(FileSelector selector) {
               if (isReference()) 
                  throw noChildrenAllowed();

               selectors.add(selector);
       }

       // *** NOT SUPPORTED (YET)
       
//     TS: bit hesitant to enable the generic FileSelector since S3File does not really implement a File.
//       
//     @Override
//     public void add(FileSelector selector) { 
//            appendSelector(selector);
//     }
 

//     TS: getting 'false negatives' on directories uploaded with older versions of jets3t.
//     
//     public void addType(TypeSelector selector) { 
//            appendSelector(selector);
//     }

         
//     TS: require being able to read an S3 file (not implemented).
//       
//     public void addModified(ModifiedSelector selector) {
//            throw new BuildException("<modified> selector not supported");
//     }
//
//     public void addContains(ContainsSelector selector) { 
//            throw new BuildException("<contains> selector not supported");
//     }
//
//     public void addContainsRegexp(ContainsRegexpSelector selector) {
//            throw new BuildException("<containsregexp> selector not supported");
//     }
//
//     public void addDifferent(DifferentSelector selector) { 
//            throw new BuildException("<different> selector not supported");
//     }
         
       // IMPLEMENTATION

       /** Makes this instance in effect a reference to another instance.
         * <p>
         * You must not set another attribute or nest elements inside this element if you make it a reference.</p>
         * 
         * @param  r the <code>Reference</code> to use.
         * 
         * @throws BuildException on error
         */
       @Override
       public void setRefid(Reference r) throws BuildException {
              if ((bucket != null) || defaultPatterns.hasPatterns(getProject())) 
                 throw tooManyAttributes();

              if (!additionalPatterns.isEmpty()) 
                 throw noChildrenAllowed();

              if (!selectors.isEmpty()) 
                 throw noChildrenAllowed();

              super.setRefid(r);
       }


       /** ResourceCollection-like <code>iterator</code> implementation. Scans the S3 bucket to find matching objects and returns
         * an iterator for the resulting list.
         * 
         * @param service Initialised service to use for access to S3.
         * 
         * @throws BuildException on error
         */
       public Iterator<S3File> iterator(S3Service service) { 
              if (isReference()) 
                 return ((S3FileSet) getCheckedRef(getProject())).iterator(service);

              calculateSet(service);

              return included.iterator();
       }

       /** ResourceCollection-like <code>size</code> implementation. Scans the S3 bucket to find matching objects and returns
         * the size of the resulting list.
         * 
         * @param service Initialised service to use for access to S3.
         * 
         * @throws BuildException on error
         */
       public int size(S3Service service) { 
              if (isReference()) 
                return ((S3FileSet) getCheckedRef(getProject())).size(service);

              calculateSet(service);

              return included.size();
       }

       /** Performs the check for circular references and returns the referenced
         * S3FileSet.
         * 
         */
       private S3FileSet getRef(Project project) {
               return (S3FileSet) getCheckedRef(project);
       }

       /** Scans the S3 bucket and then filters the object list using the includes and excludes patterns followed
         * by the selector filters.
         * 
         * @param service Initialised service to use for access to S3.
         * 
         */
       private synchronized void calculateSet(S3Service service) { 
               checkParameters();

               // ... cached ?

               if (service == null)
                  throw new BuildException("Uninitialized S3 service");

               if (included != null)
                  return;

               // ... scan and select 

               included = new ArrayList<S3File>();

               try { Set<S3File> objects = scan(getProject(),service);

                     for (S3File object: objects) { 
                         if (isSelected(object.getKey(),object)) {
                            included.add(object);
                         }
                     }
                   } catch(BuildException x) {
                       throw x;
                   } catch (Exception x) { 
                       throw new BuildException(x);
                   }
       }

       /** Throws a BuildException if the <code>bucket</code> attribute has not been set.
         * 
         */
       private void checkParameters() throws BuildException { 
               if (bucket == null)
                  throw new BuildException("Missing 'bucket' attribute");
       }

       /** Matches an S3 object against the selector list. Returns <code>true</code> unless the 
         * selector list explicitly excludes it.
         * 
         */
       private boolean isSelected(String name,S3File file) { 
               File basedir = new File("");

               for (int i=0; i<selectors.size(); i++) { 
                   if (!((FileSelector) selectors.get(i)).isSelected(basedir,name,file)) { 
                      return false;
                   }
               }

               return true;
       }
                 
       /** Retrieves the object list from the S3 bucket and matches it against the include/exclude patterns.
         * 
         * @param project  Current Ant project. Used to dereference <code>reference</code> objects.
         * @param service Initialised service to use for access to S3.
         *
         * @return Set of S3File that matches the include/excude list.
         */
       private Set<S3File> scan(Project project,S3Service service) { 
               Set<S3File> included = new ConcurrentSkipListSet<S3File>();

               try {
                     // ... initialise

                     PatternSet ps       = mergePatterns(project);
                     String[]   explicit = keys(files);
                     String[]   includes = normalize(ps.getIncludePatterns(project));
                     String[]   excludes = normalize(ps.getExcludePatterns(project));

                     // ... set include/exclude lists

                     if (explicit == null)
                        explicit = new String[0];

                     if (includes == null)
                        includes = (explicit.length == 0) ? new String[] { SelectorUtils.DEEP_TREE_MATCH } : new String[0];

                     if (excludes == null)
                        excludes = new String[0];

                     // ... scan object list

                     S3Object[] list;

                     if (prefix != null)
                        list = service.listObjects(bucket, prefix, null);
                     else
                        list = service.listObjects(bucket);

                     for (S3Object object: list) { 
                         String  key      = object.getKey(); 
                         boolean selected = false;
                         boolean include  = false;
                         boolean exclude  = false;

                         // ... hack to get wildcard match on objects in the root of the bucket 
                         //     (e.g. includes="**/xxx.bak" when xxx.bak is in the bucket root)

                         if (!key.startsWith(".") && !key.startsWith("/"))
                             key = "/" + key;                                  

                         for (String pattern: explicit) { 
                             if (SelectorUtils.match(pattern, key))
                                selected = true;
                             }

                       for (String pattern: includes) { 
                           if (SelectorUtils.match(pattern, key))
                              include = true;
                           }

                       for (String pattern: excludes) {
                           if (SelectorUtils.match(pattern, key))
                              exclude = true;
                           }

                       if (selected || (include && !exclude))
                          included.add(new S3File(object));
                     }

                     return included;
                   } catch (BuildException x) {
                       throw x;
                   } catch (Exception x) { 
                       throw new BuildException(x);
                   }
       }

       /** Converts a array of S3File to the equivalent list of S3 object keys.
         * 
         */
       private static String[] keys(List<S3File> files) { 
               String[] keys  = new String[files == null ? 0 : files.size()];
               int      index = 0;

               if (files != null) {
                  for (S3File file: files) { 
                      String key = file.getKey();

                      if (key.startsWith(".") || key.startsWith("/"))
                          keys[index++] = file.getKey();
                      else
                          keys[index++] = "/" + file.getKey();
                  }
               }

               return keys;
       }

       /** Normalises a list of include/exclude patterns to use. All '\' characters are replaced
         * by <code>/</code> to match the S3 convention.
         * <p>
         * When a pattern ends with a '/' or '\', "**" is appended.
         * 
         * @param patterns A list of include patterns. May be <code>null</code>,
         *                indicating that all objects should be included. If a non-
         *                <code>null</code> list is given, all elements must be non-
         *                <code>null</code>.
         */
       private static String[] normalize(String[] patterns) { 
               if (patterns == null) 
                   return null;

               String[] normalized = new String[patterns.length];

               for (int i=0; i<patterns.length; i++) {
                   normalized[i] = normalize(patterns[i]);
               }

               return normalized;
       }

       /** All '/' and '\' characters are replaced by <code>/</code> to match the S3 storage convention.
         * <p>
         * When a pattern ends with a '/' or '\', "**" is appended.
         * 
         */
       private static String normalize(String pattern) { 
               String string = pattern.replace('\\', '/');

               if (string.endsWith("/")) { 
                  string += SelectorUtils.DEEP_TREE_MATCH;
               }

               return string;
       }
   
       /** Merges the default and additional patternset's for this fileset.
         * 
         */
       public synchronized PatternSet mergePatterns(Project project) { 
              PatternSet ps = (PatternSet) defaultPatterns.clone();

              for (PatternSet item : additionalPatterns) {
                  ps.append(item, project);
              }

              return ps;
       }
}
