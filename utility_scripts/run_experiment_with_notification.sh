#!/bin/bash

# To run this script you first need to install and configure msmtp.


# This script runs a specified experiment command and sends a notification upon completion.
# First argument: Notification email address
# Second argument: Target project
# Remaining arguments: Additional parameters for the experiment command (including --mode)

# Function to send notification
send_notification() {
    local message=$2
    echo -e "Subject: Experiment Notification\n\n$message" | msmtp -t $1
}

# Check if the experiment arguemnts are provided
if [ $# -le 2 ]; then
    echo "Usage: $0 <experiment_arguments>"
    exit 1
fi

# Run the experiment command
experiment_command="java -jar ./annotator-core/build/libs/annotator-core-1.3.20-SNAPSHOT.jar ${@:2} > $2_$3.log 2>&1"
echo "Running experiment: $experiment_command"
eval "$experiment_command"
exit_code=$?
# Check the exit code and send appropriate notification
if [ $exit_code -eq 0 ]; then
    send_notification $1 "Experiment on project $2 with mode $3 completed successfully."
else
    send_notification $1 "Experiment on project $2 with mode $3 failed with exit code $exit_code."
fi

exit $exit_code