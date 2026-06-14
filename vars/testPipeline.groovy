def call(Map configMap)
{
    pipeline {
    agent {
        node {
            label 'RYE-TEST'
        }
    }

    environment 
    {
        APP_VERSION = ""
        ACC_ID  = "896328676768"
        ACC_REGION = "us-east-1"
        PROJECT = "${configMap.project}"
        COMPONENT = "${configMap.COMPONENT}"
    }

    options
    {   disableConcurrentBuilds()
        timeout(time: 10 , unit: 'MINUTES')

    }

    parameters
    {

        booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Deploy to selected environment')

    }

    stages {

    stage('Read Version') 
    {
            steps {
                script {
                    def packageJson = readJSON file: "APP/FRONTEND/V2/lms-platform/${env.COMPONENT}/package.json"
                    env.APP_VERSION = packageJson['version']
                    echo "Version is ${env.APP_VERSION}"
                }
            }
    }

   stage('sonarQube Analysis') 
   {
    steps {
        script {
            def scannerHome = tool 'sonar:8.0'   //scanner

            withSonarQubeEnv('sonar-server') {   //location for sonar-properties file  //server
                sh """
                cd APP/FRONTEND/V2/lms-platform/${env.COMPONENT}     
                ${scannerHome}/bin/sonar-scanner
                
                """
            }
        }
    }
   }


    stage('Quality Gate')
    {
        steps{
            timeout(time: 5, unit: 'MINUTES') {
                waitForQualityGate abortPipeline: true

                sh """
                echo "Kindly check the quality gate status in SonarQube dashboard in the link "

                """
                
            }
        }
    }

    stage('Dependabot Scan') 
    {
        steps {

        withCredentials([string(credentialsId: 'git-hub-token-dependabot', variable: 'GITHUB_TOKEN')]) {
   

        sh '''
        ALERTS=$(curl -s \
        -H "Accept: application/vnd.github+json" \
        -H "Authorization: Bearer ${GITHUB_TOKEN}" \
        -H "X-GitHub-Api-Version: 2022-11-28" \
        "https://api.github.com/repos/sundeep-balaveni/RYE-PROJECT-POC/dependabot/alerts?state=open")

        COUNT=$(echo "$ALERTS" | jq '[.[] | select(.security_advisory.severity=="high" or .security_advisory.severity=="critical")] | length')

        echo "High/Critical Alerts: $COUNT"

        if [ "$COUNT" -gt 3 ]; then
            echo "High/Critical Dependabot vulnerabilities found!"
            exit 1
        fi
        '''
     }
     }
    }


    stage('Building Docker Image') 
    {

            steps {
                script {
                    withAWS(credentials: 'ecr-creds', region: 'us-east-1') {

                    sh """
                    cd  APP/FRONTEND/V2/lms-platform/${env.COMPONENT}
                   
                    docker build -t ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com/${env.PROJECT}/${env.COMPONENT}:${env.APP_VERSION} . 
                    

                    """
                    }


                }
            }

    }

    stage('trivy scan')
    {
        steps{
            script {
                withAWS(credentials: 'ecr-creds', region: 'us-east-1') {

                    sh """

                    echo ""

                    echo "----------------------------------------------trivy report---------------------------"
        
                    aws ecr get-login-password --region ${ACC_REGION} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com
                    docker run --rm \
        -v /var/run/docker.sock:/var/run/docker.sock \
        aquasec/trivy:latest image \
        --severity HIGH,CRITICAL \
        ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com/${env.PROJECT}/${env.COMPONENT}:${env.APP_VERSION}

        echo "----------------------------------------------trivy report ended---------------------------"

                    """
                }
            }
        }
    }

    stage('Conformation to push the Docker image')
    {
        steps
        {

             when {
                expression { "${params.DEPLOY}" == "true" }
            }

            input {
                message "Do you want to Push the image to ECR"
                
            }

           
        }
    }




    stage('Push to ECR') 
    {

        steps {
            script {
                withAWS(credentials: 'ecr-creds', region: 'us-east-1') {

                    sh """
                  
                    aws ecr get-login-password --region ${ACC_REGION} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com
                    docker push ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com/${env.PROJECT}/${env.COMPONENT}:${env.APP_VERSION}
                    

                    """
                }
            }
        }
    }

    stage('Testing') 
    {
            steps {
                echo "Running tests"
            }
        }
    }




}








}