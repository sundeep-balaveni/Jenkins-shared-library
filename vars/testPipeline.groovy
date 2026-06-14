def call(Map configMap)
{

    pipeline {
        agent any

        stages {
            stage('Test') {
                steps {
                    echo "Running tests for ${configMap.project} - ${configMap.comonent} - ${configMap.repo} - ${configMap.branch}"
                }
            }
        }
    }







}