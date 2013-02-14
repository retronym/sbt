#!/bin/sh

BASE=idea

COMPILER=$BASE/incremental-compiler.jar
SOURCES=$BASE/compiler-interface-sources.jar
INTERFACE=$BASE/sbt-interface.jar

if [ -d $BASE ]; then
	if ls $BASE/* &> /dev/null; then
		echo Cleaning the output directory...
		rm -r $BASE/*
	fi
else
	echo Creating an output directory...
	mkdir $BASE
fi

echo -e "Creating a JAR with SBT compiler implementation:\t$COMPILER ..."
jar -cf $COMPILER -C compile/target/classes .
jar -uf $COMPILER -C compile/api/target/classes .
jar -uf $COMPILER -C compile/inc/target/classes .
jar -uf $COMPILER -C compile/integration/target/classes .
jar -uf $COMPILER -C compile/persist/target/classes .

jar -uf $COMPILER -C util/classfile/target/classes .
jar -uf $COMPILER -C util/classpath/target/classes .
jar -uf $COMPILER -C util/collection/target/classes .
jar -uf $COMPILER -C util/control/target/classes .
jar -uf $COMPILER -C util/io/target/classes .
jar -uf $COMPILER -C util/log/target/classes .
jar -uf $COMPILER -C util/process/target/classes .
jar -uf $COMPILER -C util/relation/target/classes .

TEMP_SBINARY=$(mktemp -d /tmp/sbinary.XXXX)
(cd $TEMP_SBINARY && jar -xf $HOME/.ivy2/cache/org.scala-tools.sbinary/sbinary_2.10/jars/sbinary_2.10-0.4.2.jar)
jar -uf $COMPILER -C $TEMP_SBINARY .


echo -e "Creating a JAR with SBT compiler interface sources:\t$SOURCES ..."
jar -cf $SOURCES -C compile/interface/src/main/scala/xsbt .

echo -e "Copying a JAR with SBT compiler interface API:\t\t$INTERFACE ..."
cp interface/target/interface-0.13.0.jar $INTERFACE

echo "Done."
