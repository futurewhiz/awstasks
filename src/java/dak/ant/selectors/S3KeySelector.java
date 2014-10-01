package dak.ant.selectors;

import java.io.File;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.types.Parameter;
import org.apache.tools.ant.types.RegularExpression;
import org.apache.tools.ant.types.selectors.BaseExtendSelector;
import org.apache.tools.ant.types.selectors.SelectorUtils;
import org.apache.tools.ant.util.regexp.Regexp;
import org.apache.tools.ant.util.regexp.RegexpMatcher;

/** Selector that filters S3 objects based on the key. 
  * <p>
  * Basically copied from Ant's FilenameSelector to provide a regular expression selector
  * for S3 objects for Eclipse users (since Eclipse ships with Ant 1.7 and the regex 
  * implementation for FilenameSelector is only available starting with Ant 1.8).
  * 
  * @author Tony Seebregts
  */
public class S3KeySelector extends BaseExtendSelector { 
    
       // CONSTANTS
    
       public static final String NAME_KEY   = "name";
       public static final String NEGATE_KEY = "negate";
       public static final String CASE_KEY   = "casesensitive";
       public static final String REGEX_KEY  = "regex";

       // INSTANCE VARIABLES

       private String  pattern;
       private String  regex;
       private boolean negated       = false;
       private boolean casesensitive = true;

       private RegularExpression reg;
       private Regexp            expression;

       // CONSTRUCTOR

       /** Creates a new <code>S3KeySelector</code> instance.
         *
         */
       public S3KeySelector() { 
       }


       // SELECTOR ATTRIBUTES
         
       /** The S3 object key (or the pattern for the object key) to be be used for selection.
        *
        * @param pattern the pattern that any S3 object key must match against in order to be selected.
        */
       public void setName(String pattern) { 
              pattern = pattern.replace('/', File.separatorChar).replace('\\',File.separatorChar);

              if (pattern.endsWith(File.separator)) { 
                 pattern += "**";
              }

              this.pattern = pattern;
       }

         
       /** The regular expression the object key will be matched against.
         *
         * @param pattern the regular expression that any object key must match against in order to be selected.
         */
       public void setRegex(String pattern) { 
              this.regex = pattern;
              this.reg   = null;
       }

       /** Whether to ignore case when checking filenames.
         *
         * @param casesensitive whether to pay attention to case sensitivity
         */
       public void setCasesensitive(boolean casesensitive) { 
              this.casesensitive = casesensitive;
       }

       /** Optionally reverse the selection of this selector, thereby emulating an &lt;exclude&gt; tag, by setting the attribute
         * negate to true. This is identical to surrounding the selector
         * with &lt;not&gt;&lt;/not&gt;.
         *
         * @param negated whether to negate this selection
         */
       public void setNegate(boolean negated) { 
              this.negated = negated;
       }

       /** When using this as a custom selector, this method will be called.
         * It translates each parameter into the appropriate setXXX() call.
         *
         * @param parameters the complete set of parameters for this selector
         */
       public void setParameters(Parameter[] parameters) { 
              super.setParameters(parameters);

              if (parameters != null) { 
                 for (int i=0; i<parameters.length; i++) { 
                     String paramname = parameters[i].getName();

                     if (NAME_KEY.equalsIgnoreCase(paramname)) 
                        setName(parameters[i].getValue());
                     else if (NEGATE_KEY.equalsIgnoreCase(paramname)) 
                        setNegate(Project.toBoolean(parameters[i].getValue()));
                     else if (REGEX_KEY.equalsIgnoreCase(paramname)) 
                        setRegex(parameters[i].getValue());
                     else if (CASE_KEY.equalsIgnoreCase(paramname)) 
                        setCasesensitive(Project.toBoolean(parameters[i].getValue()));
                     else 
                        setError("Invalid parameter " + paramname);
                 }
              }
       }

       // IMPLEMENTATION

       /** Validates that either the name or the regex attribute has been set.
         *
         */
       @Override
       public void verifySettings() {
              if ((pattern == null) && (regex == null))
                 setError("The name or regex attribute is required");
              else if ((pattern != null) && (regex != null)) 
                 setError("Only one of name and regex attribute is allowed");
       }

       /** Decides on the inclusion of an S3 object in a particular fileset. Most of the work
         * for this selector is off-loaded into SelectorUtils, a static class that provides 
         * the same services for both FilenameSelector and DirectoryScanner.
         *
         * @param basedir   Ignored.
         * @param key       S3 object key to check.
         * @param file      Ignored.
         * 
         * @return whether the object should be selected or not
         */
       @Override
       public boolean isSelected(File basedir,String key,File file) {
              validate();

              if (pattern != null) {
                 return SelectorUtils.matchPath(pattern,key,true) == !negated;
              }

              if (reg == null) {
                 reg = new RegularExpression();
                 reg.setPattern(regex);
                 expression = reg.getRegexp(getProject());
              }

              return expression.matches(key,casesensitive ? 0 : RegexpMatcher.MATCH_CASE_INSENSITIVE) == !negated;
       }

       // *** Object ***

       /** Returns a human readable selector description.
         * 
         * @return { keyselector name:(pattern|regex) [negate] [casesensitive] }
         */
       @Override
       public String toString() {
              StringBuffer string = new StringBuffer("{keyselector name: ");

              if (pattern != null) {
                 string.append(pattern);
              }

              if (regex != null) {
                 string.append(regex).append(" [as regular expression]");
              }

              string.append(" negate: ").append(negated);
              string.append(" casesensitive: ").append(casesensitive);
              string.append("}");

              return string.toString();
       }
}

