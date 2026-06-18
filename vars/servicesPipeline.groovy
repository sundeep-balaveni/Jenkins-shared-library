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
        component = "${configMap.component}"
        SERVICE_PATH = "${configMap.servicepath}"
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

    // stage('Read Version') 
    // {
    //         steps {
    //             script {
    //                 def packageJson = readJSON file: "APP/FRONTEND/V2/lms-platform/${env.component}/package.json"
    //                 env.APP_VERSION = packageJson['version']
    //                 echo "Version is ${env.APP_VERSION}"
    //             }
    //         }
    // }

    stage('Read Version') {
    steps {
        script {
            def packageJson = readJSON file: "${env.SERVICE_PATH}/${env.component}"

            echo "PACKAGE_JSON=${packageJson}"
            echo "VERSION=${packageJson.version}"

            env.APP_VERSION = packageJson.version

            echo "APP_VERSION=${env.APP_VERSION}"
        }
    }
}

    stage('Install Dependencies') {
    steps {
        sh '''

        cd "${env.SERVICE_PATH}/${env.component}"
        echo pwd
        npm install

echo "===== JEST ====="
npm list jest

echo "===== BIN ====="
ls -la node_modules/.bin | grep jest

echo "===== NODE_ENV ====="
echo $NODE_ENV





        '''
    }
}

stage('Unit Tests') {
    steps {
        sh '''
        cd "${env.SERVICE_PATH}/${env.component}"

        export NODE_ENV=test

        ./node_modules/.bin/jest --coverage
        '''
    }
}



   stage('sonarQube Analysis') 
   {
    steps {
        script {
            def scannerHome = tool 'sonar:8.0'   //scanner

            withSonarQubeEnv('sonar-server') {   //location for sonar-properties file  //server
                sh """
               cd "${env.SERVICE_PATH}/${env.component}"     
                ${scannerHome}/bin/sonar-scanner
                
                """
            }
        }
    }
   }




    stage('Quality Gate')
    {
        steps{
            timeout(time: 20, unit: 'MINUTES') {
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
                   cd "${env.SERVICE_PATH}/${env.component}" 

                   
                   
                    docker build -t ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com/${env.PROJECT}/${env.component}:${env.APP_VERSION} .
                    

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
        ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com/${env.PROJECT}/${env.component}:${env.APP_VERSION}

        echo "----------------------------------------------trivy report ended---------------------------"

                    """
                }
            }
        }
    }

stage('Approval') {
    steps {
        script {
            def choice = input(
                message: 'Push image to ECR?',
                parameters: [
                    choice(
                        name: 'ACTION',
                        choices: ['YES', 'NO'],
                        description: 'Select action'
                    )
                ]
            )

            if (choice == 'NO') {
                error("User chose not to push image")
            }
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
                    docker push ${ACC_ID}.dkr.ecr.${ACC_REGION}.amazonaws.com/${env.PROJECT}/${env.component}:${env.APP_VERSION}
                    

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