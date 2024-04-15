cd /home/daniele/github/colossium
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx256m -Xms128m" -Dspring-boot.run.arguments=tipoElaborazione=$1>> log.log 2>>err.log &
