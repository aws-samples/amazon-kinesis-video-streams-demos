ACCESS_KEY=$1
SECRET_KEY=$2
mvn package
# Create a temporary filename in /tmp directory
jar_files=$(mktemp)
# Create classpath string of dependencies from the local repository to a file
mvn -Dmdep.outputFile=$jar_files dependency:build-classpath
classpath_values=$(cat $jar_files)
# Start the consumer
java -classpath target/aws-kinesisvideo-producer-sdk-canary-consumer-1.0-SNAPSHOT.jar:$classpath_values -Daws.accessKeyId=${ACCESS_KEY} -Daws.secretKey=${SECRET_KEY} com.amazon.kinesis.video.canary.consumer.ProducerSdkCanaryConsumer $3 $4 $5 $6