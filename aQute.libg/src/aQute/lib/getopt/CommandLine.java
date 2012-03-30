package aQute.lib.getopt;

import java.lang.reflect.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.*;

import aQute.configurable.*;
import aQute.lib.justif.*;
import aQute.libg.generics.*;
import aQute.libg.reporter.*;

/**
 * Helps parsing command lines. This class takes target object, a primary
 * command, and a list of arguments. It will then find the command in the target
 * object. The method of this command must start with a "_" and take an
 * parameter of Options type. Usually this is an interface that extends Options.
 * The methods on this interface are options or flags (when they return
 * boolean).
 * 
 */
@SuppressWarnings("unchecked") public class CommandLine {
	static int		LINELENGTH	= 60;
	static Pattern	ASSIGNMENT	= Pattern.compile("(\\w[\\w\\d]*+)\\s*=\\s*([^\\s]+)\\s*");
	Reporter		reporter;
	Justif			justif = new Justif(60);
	
	public CommandLine(Reporter reporter) {
		this.reporter = reporter;
	}

	/**
	 * Execute a command in a target object with a set of options and arguments
	 * and returns help text if something fails. Errors are reported.
	 */

	public String execute(Object target, String cmd, List<String> input) throws Exception {

		if (cmd.equals("help")) {
			StringBuilder sb = new StringBuilder();
			Formatter f = new Formatter(sb);
			if (input.isEmpty())
				help(f, target);
			else {
				for (String s : input) {
					help(f, target, s);
				}
			}
			f.flush();
			justif.wrap(sb);
			return sb.toString();
		}

		//
		// Find the appropriate method
		//

		List<String> arguments = new ArrayList<String>(input);
		Map<String, Method> commands = getCommands(target);

		Method m = commands.get(cmd);
		if (m == null) {
			reporter.error("No such command %s\n", cmd);
			return help(target, null, null);
		}

		//
		// Parse the options
		//

		Class<? extends Options> optionClass = (Class<? extends Options>) m.getParameterTypes()[0];
		Options options = getOptions(optionClass, arguments);
		if (options == null) {
			// had some error, already reported
			return help(target, cmd, null);
		}

		// Check if we have an @Arguments annotation that
		// provides patterns for the remainder arguments

		Arguments argumentsAnnotation = optionClass.getAnnotation(Arguments.class);
		if (argumentsAnnotation != null) {
			String[] patterns = argumentsAnnotation.arg();

			// Check for commands without any arguments

			if (patterns.length == 0 && arguments.size() > 0) {
				reporter.error("This command takes no arguments but found %s\n", arguments);
				return help(target, cmd, null);
			}

			// Match the patterns to the given command line

			int i = 0;
			for (; i < patterns.length; i++) {
				String pattern = patterns[i];

				boolean optional = pattern.matches("\\[.*\\]");

				// Handle vararg

				if (pattern.equals("...")) {
					i = Integer.MAX_VALUE;
					break;
				}

				// Check if we're running out of args

				if (i > arguments.size()) {
					if (!optional)
						reporter.error("Missing argument %s\n", patterns[i]);
					return help(target, cmd, optionClass);
				}
			}

			// Check if we have unconsumed arguments left

			if (i < arguments.size()) {
				reporter.error("Too many arguments specified %s, expecting %s\n", arguments,
						Arrays.asList(patterns));
				return help(target, cmd, optionClass);
			}
		}
		if (reporter.getErrors().size() == 0) {
			m.setAccessible(true);
			m.invoke(target, options);
			return null;
		} else
			return help(target, cmd, optionClass);
	}

	private String help(Object target, String cmd, Class<? extends Options> type) throws Exception {
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);
		if (cmd == null)
			help(f, target);
		else if (type == null)
			help(f, target, cmd);
		else
			help(f, target, cmd, type);

		f.flush();
		justif.wrap(sb);
		return sb.toString();
	}

	/**
	 * Parse the options in a command line and return an interface that provides
	 * the options from this command line. This will parse up to (and including)
	 * -- or an argument that does not start with -
	 * 
	 */
	public <T extends Options> T getOptions(Class<T> specification, List<String> arguments)
			throws Exception {
		Map<String, String> properties = Create.map();
		Map<String, Object> values = new HashMap<String, Object>();
		Map<String, Method> options = getOptions(specification);

		argloop: while (arguments.size() > 0) {

			String option = arguments.get(0);

			if (option.startsWith("-")) {

				arguments.remove(0);

				if (option.startsWith("--")) {

					if ("--".equals(option))
						break argloop;

					// Full named option, e.g. --output
					String name = option.substring(2);
					Method m = options.get(name);
					if (m == null)
						reporter.error("Unrecognized option %s\n", name);
					else
						assignOptionValue(values, m, arguments, true);

				} else {

					// Set of single character named options like -a

					charloop: for (int j = 1; j < option.length(); j++) {

						char optionChar = option.charAt(j);

						for (String s : options.keySet()) {
							if (s.charAt(0) == optionChar) {
								Method m = options.get(s);
								boolean last = (j + 1) >= option.length();
								assignOptionValue(values, m, arguments, last);
								continue charloop;
							}
						}
						reporter.error("No such option -%s\n", optionChar);
					}
				}
			} else {
				Matcher m = ASSIGNMENT.matcher(option);
				if (m.matches()) {
					properties.put(m.group(1), m.group(2));
				}
				break;
			}
		}

		// check if all required elements are set

		for (Entry<String, Method> entry : options.entrySet()) {
			Method m = entry.getValue();
			String name = entry.getKey();
			if (!values.containsKey(name) && isMandatory(m))
				reporter.error("Required option --%s not set", name);
		}

		values.put(".", arguments);
		values.put(".command", this);
		values.put(".properties", properties);
		return Configurable.createConfigurable(specification, values);
	}

	/**
	 * Answer a list of the options specified in an options interface
	 */
	private Map<String, Method> getOptions(Class<? extends Options> interf) {
		Map<String, Method> map = new TreeMap<String, Method>();

		for (Method m : interf.getMethods()) {
			if (m.getName().startsWith("_"))
				continue;

			String name;

			Config cfg = m.getAnnotation(Config.class);
			if (cfg == null || cfg.id() == null || cfg.id().equals(Config.NULL))
				name = m.getName();
			else
				name = cfg.id();

			map.put(name, m);
		}
		return map;
	}

	/**
	 * Assign an option, must handle flags, parameters, and parameters that can
	 * happen multiple times.
	 * 
	 * @param options
	 *            The command line map
	 * @param args
	 *            the args input
	 * @param i
	 *            where we are
	 * @param m
	 *            the selected method for this option
	 * @param last
	 *            if this is the last in a multi single character option
	 * @return
	 */
	public void assignOptionValue(Map<String, Object> options, Method m, List<String> args,
			boolean last) {
		String name = m.getName();
		Type type = m.getGenericReturnType();

		if (isOption(m)) {

			// The option is a simple flag

			options.put(name, true);
		} else {

			// The option is followed by an argument

			if (!last) {
				reporter.error(
						"Option --%s not last in a set of 1-letter options (%s) but it requires an argument of type ",
						name, name.charAt(0), getTypeDescriptor(type));
				return;
			}

			if (args.isEmpty()) {
				reporter.error("Missing argument %s for option --%s, -%s ",
						getTypeDescriptor(type), name, name.charAt(0));
				return;
			}

			String parameter = args.remove(0);

			if (Collection.class.isAssignableFrom(m.getReturnType())) {

				Collection<Object> optionValues = (Collection<Object>) options.get(m.getName());

				if (optionValues == null) {
					optionValues = new ArrayList<Object>();
					options.put(name, optionValues);
				}

				optionValues.add(parameter);
			} else {

				if (options.containsKey(name)) {
					reporter.error("The option %s can only occur once", name);
					return;
				}

				options.put(name, parameter);
			}
		}
	}

	/**
	 * Provide a help text.
	 */

	public void help(Formatter f, Object target, String cmd, Class<? extends Options> specification) {
		Description descr = specification.getAnnotation(Description.class);
		Arguments patterns = specification.getAnnotation(Arguments.class);
		Map<String, Method> options = getOptions(specification);

		String specName = lastPart(specification.getName());
		if (specName.endsWith("Options"))
			specName = specName.substring(0, specName.length() - "Options".length());

		String description = descr == null ? "" : descr.value();

		f.format("NAME\n  %s - %s\n\n", cmd, description);
		f.format("SYNOPSIS\n   %s [options] ", cmd);

		if (patterns == null)
			f.format(" ...\n\n");
		else {
			String del = " ";
			for (String pattern : patterns.arg()) {
				if (pattern.equals("..."))
					f.format("%s...", del);
				else
					f.format("%s<%s>", del, pattern);
				del = " ";
			}
			f.format("\n\n");
		}

		f.format("OPTIONS\n");
		for (Entry<String, Method> entry : options.entrySet()) {
			String optionName = entry.getKey();
			Method m = entry.getValue();

			Config cfg = m.getAnnotation(Config.class);
			Description d = m.getAnnotation(Description.class);
			boolean required = isMandatory(m);

			String methodDescription = cfg != null ? cfg.description() : (d == null ? "" : d
					.value());

			f.format("   %s -%s, --%s %s%s - %s\n", required ? " " : "[", //
					optionName.charAt(0), //
					optionName, //
					getTypeDescriptor(m.getGenericReturnType()), //
					required ? " " : "]",//
					methodDescription);
		}
		f.format("\n");
	}

	static Pattern	LAST_PART	= Pattern.compile(".*[\\$\\.]([^\\$\\.]+)");

	private static String lastPart(String name) {
		Matcher m = LAST_PART.matcher(name);
		if (m.matches())
			return m.group(1);
		else
			return name;
	}

	/**
	 * Show all commands in a target
	 */
	public void help(Formatter f, Object target) throws Exception {
		f.format("Available commands: ");

		String del = "";
		for (String name : getCommands(target).keySet()) {
			f.format("%s%s", del, name);
			del = ", ";
		}
		f.format("\n");

	}

	/**
	 * Show the full help for a given command
	 */
	public void help(Formatter f, Object target, String cmd) {

		Method m = getCommands(target).get(cmd);
		if (m == null)
			f.format("No such command: %s\n", cmd);
		else {
			Class<? extends Options> options = (Class<? extends Options>) m.getParameterTypes()[0];
			help(f, target, cmd, options);
		}
	}

	/**
	 * Parse a class and return a list of command names
	 * 
	 * @param target
	 * @return
	 */
	public Map<String, Method> getCommands(Object target) {
		Map<String, Method> map = new TreeMap<String, Method>();

		for (Method m : target.getClass().getMethods()) {

			if (m.getParameterTypes().length == 1 && m.getName().startsWith("_")) {
				Class<?> clazz = m.getParameterTypes()[0];
				if (Options.class.isAssignableFrom(clazz)) {
					String name = m.getName().substring(1);
					map.put(name, m);
				}
			}
		}
		return map;
	}

	/**
	 * Answer if the method is marked mandatory
	 */
	private boolean isMandatory(Method m) {
		Config cfg = m.getAnnotation(Config.class);
		if (cfg == null)
			return false;

		return cfg.required();
	}

	/**
	 * @param m
	 * @return
	 */
	private boolean isOption(Method m) {
		return m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class;
	}

	/**
	 * Show a type in a nice way
	 */

	private String getTypeDescriptor(Type type) {
		if (type instanceof ParameterizedType) {
			ParameterizedType pt = (ParameterizedType) type;
			Type c = pt.getRawType();
			if (c instanceof Class) {
				if (Collection.class.isAssignableFrom((Class<?>) c)) {
					return getTypeDescriptor(pt.getActualTypeArguments()[0]) + "*";
				}
			}
		}
		if (!(type instanceof Class))
			return "<>";

		Class<?> clazz = (Class<?>) type;

		if (clazz == Boolean.class || clazz == boolean.class)
			return ""; // Is a flag

		return "<" + lastPart(clazz.getName().toLowerCase()) + ">";
	}

}
