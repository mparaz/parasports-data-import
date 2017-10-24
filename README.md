ParaSports Data Import
======================

This code provides a command line tool based on the Wikidata Toolkit Examples.
It imports spreadsheets in XLSX format that contain either Items or
Statements. Items must be created before Statements.
The Properties in the Statements are created manually through the Wikidata
web interface.

Requirements
------------
* The [Wikidata Toolkit](https://github.com/mparaz/Wikidata-Toolkit) fork which is not up-to-date
with the Wikimedia upstream. It is separate because it incorporates an unmerged 
pull request to support entity searching.
* Java 1.8.

Command line examples
--------------------
* `java -Xmx4096m -classpath ~/parasports_data_import-0.2.2.jar examples.ParasportsWork items items-sheet.xlsx >> ./items-output.txt`
* `java -Xmx4096m -classpath ~/parasports_data_import-0.2.2.jar examples.ParasportsWork statements statements-sheet.xlsx >> ./statements-output.txt`

Improvements
------------
* Make Wikibase username, password and URL externally configurable. 
They are currently hard-coded.
* Better understanding of the input XLSX files. It is currently a process of 
trial and error to run the program, observe the output and make modifications.
* Tests! Unit tests, acceptance tests. Right now, the test is ... running the code and looking at Wikibase.
* Code health: style, static analysis.


Original README from Wikidata Toolkit Examples:
----------------------------------------------

# Wikidata Toolkit Examples

This is an example project that shows how to set up a Java project that
uses [Wikidata Toolkit](https://github.com/Wikidata/Wikidata-Toolkit).
It contains several simple example programs and bots in the source directory.

What's found in this repository
-------------------------------

The individual examples are documented in the README file of each package.


Running examples using an IDE
-----------------------------

You can import the project into any Java IDE that supports Maven (and maybe git)
and run the example programs from there. Wikidata Toolkit provides detailed
[instructions on how to set up Eclipse for using Maven and git](https://www.mediawiki.org/wiki/Wikidata_Toolkit/Eclipse_setup).


Running examples directly using Maven
-------------------------------------

You can also run the code directly using Maven from the command line. For this,
you need to have Maven and (obviously) Java installed. To compile the project
and obtain necessary dependencies, run

```mvn compile```

Thereafter, you can run any individual example using its Java class name, for
example:

```mvn exec:java -Dexec.mainClass="examples.FetchOnlineDataExample"```

Credits and License
-------------------

This project is copied from the [Wikidata Toolkit](https://github.com/Wikidata/Wikidata-Toolkit) examples module.
Authors can be found there.

License: [Apache 2.0](LICENSE)

