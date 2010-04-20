/*
 * Copyright (c) 2010 The Broad Institute
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the �Software�), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED �AS IS�, WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
 * THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.commandline;

import org.broadinstitute.sting.utils.StingException;
import org.broadinstitute.sting.utils.collections.Pair;
import org.broadinstitute.sting.utils.classloader.JVMUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.help.ApplicationDetails;
import org.broadinstitute.sting.utils.help.HelpFormatter;
import org.apache.log4j.Logger;

import java.lang.reflect.*;
import java.util.*;

/**
 * A parser for Sting command-line arguments.
 */
public class ParsingEngine {
    /**
     * The command-line program at the heart of this parsing engine.
     */
    CommandLineProgram clp = null;

    /**
     * A collection of all the source fields which define command-line arguments.
     */
    List<ArgumentSource> argumentSources = new ArrayList<ArgumentSource>();

    /**
     * A list of defined arguments against which command lines are matched.
     * Package protected for testing access.
     */
    ArgumentDefinitions argumentDefinitions = new ArgumentDefinitions();

    /**
     * A list of matches from defined arguments to command-line text.
     * Indicates as best as possible where command-line text remains unmatched
     * to existing arguments.
     */
    ArgumentMatches argumentMatches = null;

    /**
     * Techniques for parsing and for argument lookup.
     */
    private List<ParsingMethod> parsingMethods = new ArrayList<ParsingMethod>();

    /**
     * our log, which we want to capture anything from org.broadinstitute.sting
     */
    protected static Logger logger = Logger.getLogger(ParsingEngine.class);

    public ParsingEngine( CommandLineProgram clp ) {
        this.clp = clp;

        parsingMethods.add( ParsingMethod.FullNameParsingMethod );
        parsingMethods.add( ParsingMethod.ShortNameParsingMethod );

        // Null check for unit tests.  Perhaps we should mock up an empty CLP?
        if( clp != null )
            ArgumentTypeDescriptor.addDescriptors( clp.getArgumentTypeDescriptors() );
    }

    /**
     * Add a main argument source.  Argument sources are expected to have
     * any number of fields with an @Argument annotation attached.
     * @param source     An argument source from which to extract command-line arguments.
     */
    public void addArgumentSource( Class source ) {
        addArgumentSource(null, source);
    }

    /**
     * Add an argument source.  Argument sources are expected to have
     * any number of fields with an @Argument annotation attached.
     * @param sourceName name for this argument source.  'Null' indicates that this source should be treated
     *                   as the main module.
     * @param sourceClass A class containing argument sources from which to extract command-line arguments.
     */
    public void addArgumentSource( String sourceName, Class sourceClass ) {
        List<ArgumentDefinition> argumentsFromSource = new ArrayList<ArgumentDefinition>();
        for( ArgumentSource argumentSource: extractArgumentSources(sourceClass) )
            argumentsFromSource.addAll( argumentSource.createArgumentDefinitions() );
        argumentDefinitions.add( new ArgumentDefinitionGroup(sourceName, argumentsFromSource) );
    }

    /**
     * Do a cursory search to see if an argument with the given name is present.
     * @param argumentFullName full name of the argument.
     * @return True if the argument is present.  False otherwise.
     */
    public boolean isArgumentPresent( String argumentFullName ) {
        ArgumentDefinition definition =
                argumentDefinitions.findArgumentDefinition(argumentFullName,ArgumentDefinitions.FullNameDefinitionMatcher);
        return argumentMatches.hasMatch(definition);

    }

    /**
     * Parse the given set of command-line arguments, returning
     * an ArgumentMatches object describing the best fit of these
     * command-line arguments to the arguments that are actually
     * required.
     * @param tokens Tokens passed on the command line.
     * @return A object indicating which matches are best.  Might return
     *         an empty object, but will never return null.
     */
    public void parse( String[] tokens ) {
        argumentMatches = new ArgumentMatches();

        int lastArgumentMatchSite = -1;

        for( int i = 0; i < tokens.length; i++ ) {
            String token = tokens[i];
            // If the token is of argument form, parse it into its own argument match.
            // Otherwise, pair it with the most recently used argument discovered.
            if( isArgumentForm(token) ) {
                ArgumentMatch argumentMatch = parseArgument( token, i );
                if( argumentMatch != null ) {
                    argumentMatches.mergeInto( argumentMatch );
                    lastArgumentMatchSite = i;
                }
            }
            else {
                if( argumentMatches.hasMatch(lastArgumentMatchSite) &&
                    !argumentMatches.getMatch(lastArgumentMatchSite).hasValueAtSite(lastArgumentMatchSite))
                    argumentMatches.getMatch(lastArgumentMatchSite).addValue( lastArgumentMatchSite, token );
                else
                    argumentMatches.MissingArgument.addValue( i, token );

            }
        }
    }

    public enum ValidationType { MissingRequiredArgument,
                                 InvalidArgument,
                                 InvalidArgumentValue,
                                 ValueMissingArgument,
                                 TooManyValuesForArgument,
                                 MutuallyExclusive }

    /**
     * Validates the list of command-line argument matches.
     */
    public void validate() {
        validate( EnumSet.noneOf(ValidationType.class) );
    }

    /**
     * Validates the list of command-line argument matches.  On failure throws an exception with detailed info about the
     * particular failures.  Takes an EnumSet indicating which validation checks to skip.
     * @param skipValidationOf List of validation checks to skip.
     */
    public void validate( EnumSet<ValidationType> skipValidationOf ) {
        // Find missing required arguments.
        if( !skipValidationOf.contains(ValidationType.MissingRequiredArgument) ) {
            Collection<ArgumentDefinition> requiredArguments =
                    argumentDefinitions.findArgumentDefinitions( true, ArgumentDefinitions.RequiredDefinitionMatcher );
            Collection<ArgumentDefinition> missingArguments = new ArrayList<ArgumentDefinition>();
            for( ArgumentDefinition requiredArgument: requiredArguments ) {
                if( !argumentMatches.hasMatch(requiredArgument) )
                    missingArguments.add( requiredArgument );
            }

            if( missingArguments.size() > 0 )
                throw new MissingArgumentException( missingArguments );
        }

        // Find invalid arguments.  Invalid arguments will have a null argument definition.
        if( !skipValidationOf.contains(ValidationType.InvalidArgument) ) {
            ArgumentMatches invalidArguments = argumentMatches.findUnmatched();
            if( invalidArguments.size() > 0 )
                throw new InvalidArgumentException( invalidArguments );
        }

        // Find invalid argument values (arguments that fail the regexp test.
        if( !skipValidationOf.contains(ValidationType.InvalidArgumentValue) ) {
            Collection<ArgumentDefinition> verifiableArguments = 
                    argumentDefinitions.findArgumentDefinitions( null, ArgumentDefinitions.VerifiableDefinitionMatcher );
            Collection<Pair<ArgumentDefinition,String>> invalidValues = new ArrayList<Pair<ArgumentDefinition,String>>();
            for( ArgumentDefinition verifiableArgument: verifiableArguments ) {
                ArgumentMatches verifiableMatches = argumentMatches.findMatches( verifiableArgument );
                for( ArgumentMatch verifiableMatch: verifiableMatches ) {
                    for( String value: verifiableMatch.values() ) {
                        if( !value.matches(verifiableArgument.validation) )
                            invalidValues.add( new Pair<ArgumentDefinition,String>(verifiableArgument, value) );
                    }
                }
            }

            if( invalidValues.size() > 0 )
                throw new InvalidArgumentValueException( invalidValues );
        }

        // Find values without an associated mate.
        if( !skipValidationOf.contains(ValidationType.ValueMissingArgument) ) {
            if( argumentMatches.MissingArgument.values().size() > 0 )
                throw new UnmatchedArgumentException( argumentMatches.MissingArgument );
        }

        // Find arguments with too many values.
        if( !skipValidationOf.contains(ValidationType.TooManyValuesForArgument)) {
            Collection<ArgumentMatch> overvaluedArguments = new ArrayList<ArgumentMatch>();
            for( ArgumentMatch argumentMatch: argumentMatches.findSuccessfulMatches() ) {
                // Warning: assumes that definition is not null (asserted by checks above).
                if( !argumentMatch.definition.isMultiValued && argumentMatch.values().size() > 1 )
                    overvaluedArguments.add(argumentMatch);
            }

            if( !overvaluedArguments.isEmpty() )
                throw new TooManyValuesForArgumentException(overvaluedArguments);
        }

        // Find sets of options that are supposed to be mutually exclusive.
        if( !skipValidationOf.contains(ValidationType.MutuallyExclusive)) {
            Collection<Pair<ArgumentMatch,ArgumentMatch>> invalidPairs = new ArrayList<Pair<ArgumentMatch,ArgumentMatch>>();
            for( ArgumentMatch argumentMatch: argumentMatches.findSuccessfulMatches() ) {
                if( argumentMatch.definition.exclusiveOf != null ) {
                    for( ArgumentMatch conflictingMatch: argumentMatches.findSuccessfulMatches() ) {
                        // Skip over the current element.
                        if( argumentMatch == conflictingMatch )
                            continue;
                        if( argumentMatch.definition.exclusiveOf.equals(conflictingMatch.definition.fullName) ||
                            argumentMatch.definition.exclusiveOf.equals(conflictingMatch.definition.shortName))
                            invalidPairs.add( new Pair<ArgumentMatch,ArgumentMatch>(argumentMatch, conflictingMatch) );
                    }
                }
            }

            if( !invalidPairs.isEmpty() )
                throw new ArgumentsAreMutuallyExclusiveException( invalidPairs );
        }
    }

    /**
     * Loads a set of matched command-line arguments into the given object.
     * @param object Object into which to add arguments.
     */
    public void loadArgumentsIntoObject( Object object ) {
        List<ArgumentSource> argumentSources = extractArgumentSources(object.getClass());
        for( ArgumentSource argumentSource: argumentSources )
            loadValueIntoObject( argumentSource, object, argumentMatches.findMatches(argumentSource) );
    }

    /**
     * Loads a single argument into the object and that objects children.
     * @param argumentMatches Argument matches to load into the object.
     * @param source Argument source to load into the object.
     * @param instance Object into which to inject the value.  The target might be in a container within the instance.
     */
    private void loadValueIntoObject( ArgumentSource source, Object instance, ArgumentMatches argumentMatches ) {
        // Nothing to load
        if( argumentMatches.size() == 0 && !source.overridesDefault())
            return;

        // Target instance into which to inject the value.
        List<Object> targets = new ArrayList<Object>();

        // Check to see whether the instance itself can be the target.
        if( source.clazz.isAssignableFrom(instance.getClass()) ) {
            targets.add(instance);
        }

        // Check to see whether a contained class can be the target.
        targets.addAll(getContainersMatching(instance,source.clazz));

        // Abort if no home is found for the object.
        if( targets.size() == 0 )
            throw new StingException("Internal command-line parser error: unable to find a home for argument matches " + argumentMatches);

        for( Object target: targets ) {
            Object value = (argumentMatches.size() != 0) ? source.parse(source,argumentMatches) : source.getDefault();
            JVMUtils.setFieldValue(source.field,target,value);
        }
    }

    /**
     * Prints out the help associated with these command-line argument definitions.
     * @param applicationDetails Details about the specific GATK-based application being run.
     */
    public void printHelp( ApplicationDetails applicationDetails ) {
        new HelpFormatter().printHelp(applicationDetails,argumentDefinitions);
    }

    /**
     * Extract all the argument sources from a given object.
     * @param sourceClass class to act as sources for other arguments.
     * @return A list of sources associated with this object and its aggregated objects.
     */
    protected static List<ArgumentSource> extractArgumentSources(Class sourceClass) {
        List<ArgumentSource> argumentSources = new ArrayList<ArgumentSource>();
        while( sourceClass != null ) {
            Field[] fields = sourceClass.getDeclaredFields();
            for( Field field: fields ) {
                if( field.isAnnotationPresent(Argument.class) )
                    argumentSources.add( new ArgumentSource(sourceClass,field) );
                if( field.isAnnotationPresent(ArgumentCollection.class) )
                    argumentSources.addAll( extractArgumentSources(field.getType()) );
            }
            sourceClass = sourceClass.getSuperclass();
        }
        return argumentSources;
    }    

    /**
     * Determines whether a token looks like the name of an argument.
     * @param token Token to inspect.  Can be surrounded by whitespace.
     * @return True if token is of short name form.
     */
    private boolean isArgumentForm( String token ) {
        for( ParsingMethod parsingMethod: parsingMethods ) {
            if( parsingMethod.matches(token) )
                return true;
        }

        return false;
    }

    /**
     * Parse a short name into an ArgumentMatch.
     * @param token The token to parse.  The token should pass the isLongArgumentForm test.
     * @param position The position of the token in question.
     * @return ArgumentMatch associated with this token, or null if no match exists.
     */    
    private ArgumentMatch parseArgument( String token, int position ) {
        if( !isArgumentForm(token) )
            throw new IllegalArgumentException( "Token is not recognizable as an argument: " + token );

        for( ParsingMethod parsingMethod: parsingMethods ) {
            if( parsingMethod.matches( token ) )
                return parsingMethod.match( argumentDefinitions, token, position );
        }

        // No parse results found.
        return null;
    }

    /**
     * Gets a list of the container instances of the given type stored within the given target.
     * @param target Class holding the container.
     * @param type Container type.
     * @return A list of containers matching the given type.
     */
    private List<Object> getContainersMatching(Object target, Class<?> type) {
        List<Object> containers = new ArrayList<Object>();

        Field[] fields = target.getClass().getDeclaredFields();
        for( Field field: fields ) {
            if( field.isAnnotationPresent(ArgumentCollection.class) && type.isAssignableFrom(field.getType()) )
                containers.add(JVMUtils.getFieldValue(field,target));
        }

        return containers;
    }
}

/**
 * An exception indicating that some required arguments are missing.
 */
class MissingArgumentException extends ArgumentException {
    public MissingArgumentException( Collection<ArgumentDefinition> missingArguments ) {
        super( formatArguments(missingArguments) );
    }

    private static String formatArguments( Collection<ArgumentDefinition> missingArguments ) {
        StringBuilder sb = new StringBuilder();
        for( ArgumentDefinition missingArgument: missingArguments ) {
            if( missingArgument.shortName != null )
                sb.append( String.format("%nArgument with name '--%s' (-%s) is missing.", missingArgument.fullName, missingArgument.shortName) );
            else
                sb.append( String.format("%nArgument with name '--%s' is missing.", missingArgument.fullName) );
        }
        return sb.toString();
    }
}

class MissingArgumentValueException extends ArgumentException {
    public MissingArgumentValueException( Collection<ArgumentDefinition> missingArguments ) {
        super( formatArguments(missingArguments) );
    }

    private static String formatArguments( Collection<ArgumentDefinition> missingArguments ) {
        StringBuilder sb = new StringBuilder();
        for( ArgumentDefinition missingArgument: missingArguments ) {
            if( missingArgument.shortName != null )
                sb.append( String.format("%nValue for argument with name '--%s' (-%s) is missing.", missingArgument.fullName, missingArgument.shortName) );
            else
                sb.append( String.format("%nValue for argument with name '--%s' is missing.", missingArgument.fullName) );
            if(missingArgument.validOptions != null)
                sb.append( String.format("  Valid options are (%s).", Utils.join(",",missingArgument.validOptions)));
        }
        return sb.toString();
    }
}

/**
 * An exception for undefined arguments.
 */
class InvalidArgumentException extends ArgumentException {
    public InvalidArgumentException( ArgumentMatches invalidArguments ) {
        super( formatArguments(invalidArguments) );
    }

    private static String formatArguments( ArgumentMatches invalidArguments ) {
        StringBuilder sb = new StringBuilder();
        for( ArgumentMatch invalidArgument: invalidArguments )
            sb.append( String.format("%nArgument with name '%s' isn't defined.", invalidArgument.label) );
        return sb.toString();
    }
}

/**
 * An exception for values whose format is invalid.
 */
class InvalidArgumentValueException extends ArgumentException {
    public InvalidArgumentValueException( Collection<Pair<ArgumentDefinition,String>> invalidArgumentValues ) {
        super( formatArguments(invalidArgumentValues) );
    }

    private static String formatArguments( Collection<Pair<ArgumentDefinition,String>> invalidArgumentValues ) {
        StringBuilder sb = new StringBuilder();
        for( Pair<ArgumentDefinition,String> invalidValue: invalidArgumentValues ) {
            sb.append( String.format("%nArgument '--%s' has value of incorrect format: %s (should match %s)",
                                     invalidValue.first.fullName,
                                     invalidValue.second,
                                     invalidValue.first.validation) );
        }
        return sb.toString();
    }
}


/**
 * An exception for values that can't be mated with any argument.
 */
class UnmatchedArgumentException extends ArgumentException {
    public UnmatchedArgumentException( ArgumentMatch invalidValues ) {
        super( formatArguments(invalidValues) );
    }

    private static String formatArguments( ArgumentMatch invalidValues ) {
        StringBuilder sb = new StringBuilder();
        for( int index: invalidValues.indices.keySet() )
            for( String value: invalidValues.indices.get(index) )
                sb.append( String.format("%nInvalid argument value '%s' at position %d.", value, index) );
        return sb.toString();
    }
}

/**
 * An exception indicating that too many values have been provided for the given argument.
 */
class TooManyValuesForArgumentException extends ArgumentException {
    public TooManyValuesForArgumentException( Collection<ArgumentMatch> arguments ) {
        super( formatArguments(arguments) );
    }

    private static String formatArguments( Collection<ArgumentMatch> arguments ) {
        StringBuilder sb = new StringBuilder();
        for( ArgumentMatch argument: arguments )
            sb.append( String.format("%nArgument '%s' has to many values: %s.", argument.label, Arrays.deepToString(argument.values().toArray())) );
        return sb.toString();
    }
}

/**
 * An exception indicating that mutually exclusive options have been passed in the same command line.
 */
class ArgumentsAreMutuallyExclusiveException extends ArgumentException {
    public ArgumentsAreMutuallyExclusiveException( Collection<Pair<ArgumentMatch,ArgumentMatch>> arguments ) {
        super( formatArguments(arguments) );
    }

    private static String formatArguments( Collection<Pair<ArgumentMatch,ArgumentMatch>> arguments ) {
        StringBuilder sb = new StringBuilder();
        for( Pair<ArgumentMatch,ArgumentMatch> argument: arguments )
            sb.append( String.format("%nArguments '%s' and '%s' are mutually exclusive.", argument.first.definition.fullName, argument.second.definition.fullName ) );
        return sb.toString();
    }

}


/**
 * An exception for when an argument doesn't match an of the enumerated options for that var type
 */
class UnknownEnumeratedValueException extends ArgumentException {
    public UnknownEnumeratedValueException(ArgumentDefinition definition, String argumentPassed) {
        super( formatArguments(definition,argumentPassed) );
    }

    private static String formatArguments(ArgumentDefinition definition, String argumentPassed) {
        return String.format("Invalid value %s specified for argument %s; valid options are (%s).", argumentPassed, definition.fullName, Utils.join(",",definition.validOptions));
    }
}