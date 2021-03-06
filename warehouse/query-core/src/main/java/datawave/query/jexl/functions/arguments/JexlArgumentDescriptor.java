package datawave.query.jexl.functions.arguments;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import datawave.query.jexl.JexlASTHelper;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.util.DateIndexHelper;
import datawave.query.util.MetadataHelper;

import org.apache.commons.jexl2.parser.ASTOrNode;
import org.apache.commons.jexl2.parser.JexlNode;

/**
 * This interface will describe the arguments for a jexl function that has implemented (@see JexlArgumentDescriptor). The initial use of this is to determine
 * what fields and values should be queried for in the index for shard range determination.
 *
 * 
 *
 */
public interface JexlArgumentDescriptor {
    /**
     * Get the nodes that can be used to determine ranges from the global index.
     *
     * @param metadataHelper
     * @param dateIndexHelper
     * @param datatypeFilter
     * @return The query which will be used against the global index
     */
    JexlNode getIndexQuery(ShardQueryConfiguration settings, MetadataHelper metadataHelper, DateIndexHelper dateIndexHelper, Set<String> datatypeFilter);
    
    /**
     * Get the entire set of fields that are referenced by this function. If you need subsets of fields required to satisfy the function, then use fieldSets()
     * 
     * @param metadata
     * @param datatypeFilter
     * @return the set of fields
     */
    Set<String> fields(MetadataHelper metadata, Set<String> datatypeFilter);
    
    /**
     * Get the fields separated into sets that are required to satisfy this function. So if one of the identifiers is actually an "OR" expression, then each of
     * the identifiers would be returned in a separate set.
     * 
     * @param metadata
     * @param datatypeFilter
     * @return the set of fields
     */
    Set<Set<String>> fieldSets(MetadataHelper metadata, Set<String> datatypeFilter);
    
    /**
     * Get the fields that are referenced by the specified argument in this function. Argument 0 is the first argument to the function (child node 3 of the
     * ASTFunctionNode as the first two are the namespace and function name). Note that it is very important for the string literal arguments that the set of
     * fields are correct as this will determine which normalizers are applied to that argument.
     * 
     * @param metadata
     * @param datatypeFilter
     * @param arg
     * @return the set of fields referenced by the specified arg
     */
    Set<String> fieldsForNormalization(MetadataHelper metadata, Set<String> datatypeFilter, int arg);
    
    /**
     * Should expansions (e.g. from a model) use ORs or ANDs. For example isNull should use ANDs, but includeRegex should use ORs.
     * 
     * @return true is an OR node is required
     */
    boolean useOrForExpansion();
    
    /**
     * Are the string literal arguments regexes. Used to determine how to normalize them (see {@link datawave.data.type.BaseType})
     * 
     * @return true if literal arguments are regexes
     */
    boolean regexArguments();
    
    /**
     * This class of functions can be used to support the fieldSets method implementation. They look for nested OR nodes and then produce a product of the
     * underlying identifiers to produce sets of required fields.
     */
    class Fields {
        public static Set<Set<String>> product(JexlNode a, JexlNode b) {
            return product(product(a), b);
        }
        
        public static Set<Set<String>> product(Set<Set<String>> a, JexlNode b) {
            Set<Set<String>> fieldSets = new HashSet<>();
            if (b instanceof ASTOrNode) {
                for (String field2 : JexlASTHelper.getIdentifierNames(b)) {
                    for (Set<String> fields1 : a) {
                        Set<String> fields = new HashSet<String>();
                        fields.addAll(fields1);
                        fields.add(field2);
                        fieldSets.add(fields);
                    }
                }
            } else {
                Set<String> fields2 = JexlASTHelper.getIdentifierNames(b);
                for (Set<String> fields1 : a) {
                    Set<String> fields = new HashSet<>();
                    fields.addAll(fields1);
                    fields.addAll(fields2);
                    fieldSets.add(fields);
                }
            }
            return fieldSets;
        }
        
        public static Set<Set<String>> product(Set<Set<String>> a, Set<Set<String>> b) {
            Set<Set<String>> fieldSets = new HashSet<>();
            for (Set<String> fields2 : b) {
                for (Set<String> fields1 : a) {
                    Set<String> fields = new HashSet<String>();
                    fields.addAll(fields1);
                    fields.addAll(fields2);
                    fieldSets.add(fields);
                }
            }
            return fieldSets;
        }
        
        public static Set<Set<String>> product(JexlNode a) {
            Set<Set<String>> fieldSets = new HashSet<>();
            if (a instanceof ASTOrNode) {
                for (String field : JexlASTHelper.getIdentifierNames(a)) {
                    fieldSets.add(Collections.singleton(field));
                }
            } else {
                fieldSets.add(JexlASTHelper.getIdentifierNames(a));
            }
            return fieldSets;
        }
    }
    
}
