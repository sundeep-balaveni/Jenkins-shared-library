def call(Map configMap) {

    def APP_VERSION = ""
    def SHORT_COMMIT = ""

    pipeline {
agent { node { label 'RYE-TEST' } }

    parameters {
        choice(
            name: 'deploy_to',
            choices: ['dev', 'uat', 'prod'],
            description: 'Target environment'
        )

        string(
            name: 'VERSION',
            defaultValue: '',
            description: 'Short commit SHA — set by Jira webhook for UAT/PROD'
        )

        string(
            name: 'JIRA_KEY',
            defaultValue: '',
            description: 'Jira issue key — set by Jira webhook for UAT/PROD'
        )

        string(
            name: 'CR_NUMBER',
            defaultValue: '',
            description: 'Change Request number — required for PROD deploy'
        )
    }

    triggers {
        GenericTrigger(
            genericVariables: [
                [key: 'deploy_to', value: '$.deploy_to'],
                [key: 'VERSION', value: '$.VERSION'],
                [key: 'JIRA_KEY', value: '$.JIRA_KEY'],
                [key: 'CR_NUMBER', value: '$.CR_NUMBER']
            ],
            token: "${configMap.get('project')}-main-pipeline",
            causeString: 'Triggered by Jirgita — $deploy_to deploy',
            printContributedVariables: true,
            printPostContent: true
        )
    }

environment {

    acc_id       = "160885265516"
    project      = configMap.get("project")
    component    = configMap.get("component")
    jira_project = configMap.get("jiraProject")
    region       = "us-east-1"
    CLUSTER      = "roboshop-dev"
    SERVICE_PATH = configMap.get("servicePath")
}

    stages {

        stage('Init') {
            steps {
                script {
                    env.DEPLOY_TO      = env.deploy_to ?: params.deploy_to ?: 'dev'
                    env.TARGET_VERSION = env.VERSION ?: params.VERSION ?: ''
                    env.JIRA_ISSUE     = env.JIRA_KEY ?: params.JIRA_KEY ?: ''
                    env.CR_NUMBER      = params.CR_NUMBER ?: ''

                    echo """
                    DEPLOY_TO=${env.DEPLOY_TO}
                    TARGET_VERSION=${env.TARGET_VERSION}
                    JIRA_ISSUE=${env.JIRA_ISSUE}
                    CR_NUMBER=${env.CR_NUMBER}
                    """
                }
            }
        }

        // ─────────────────────────────────────────────
        // DEV FLOW
        // Read Version → Promote Image → Create Jira
        // → Deploy DEV → Functional Tests
        // ─────────────────────────────────────────────

// stage('Read Version') {
//     when { expression { env.DEPLOY_TO == 'dev' } }

//     steps {
//         script {

//             dir("${env.SERVICE_PATH}/${component}") {
//                 APP_VERSION = utils.readAppVersion()
//             }

//             SHORT_COMMIT = sh(
//                 script: 'git rev-parse --short HEAD',
//                 returnStdout: true
//             ).trim()

//             echo "APP_VERSION=${APP_VERSION}"
//             echo "SHORT_COMMIT=${SHORT_COMMIT}"
//         }
//     }
// }
//         stage('Promote Image') {

//     when { expression { env.DEPLOY_TO == 'dev' } }

//     steps {
//         script {

//             echo "PROMOTE VERSION=${APP_VERSION}"
//             echo "PROMOTE COMMIT=${SHORT_COMMIT}"

//             withAWS(
//                 credentials: 'ecr-creds',
//                 region: "${region}"
//             ) {

//                 sh """
//                     aws ecr get-login-password --region ${region} \
//                     | docker login --username AWS --password-stdin \
//                     ${acc_id}.dkr.ecr.${region}.amazonaws.com

//                     docker pull \
//                     ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${APP_VERSION}

//                     docker tag \
//                     ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${APP_VERSION} \
//                     ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${SHORT_COMMIT}

//                     docker push \
//                     ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${SHORT_COMMIT}
//                 """
//             }
//         }
//     }
// }
stage('Create Jira Ticket') {
    steps {
        script {

            withCredentials([
                string(credentialsId: 'jira-url', variable: 'JIRA_URL'),
                usernamePassword(
                    credentialsId: 'jira-creds',
                    usernameVariable: 'JIRA_EMAIL',
                    passwordVariable: 'JIRA_TOKEN'
                )
            ]) {

                sh """
cat > issue.json <<EOF
{
  "fields": {
    "project": {
      "key": "SCRUM"
    },
    "summary": "Auth Service Test Ticket",
    "issuetype": {
      "name": "Task"
    },
    "description": {
      "type": "doc",
      "version": 1,
      "content": [{
        "type": "paragraph",
        "content": [{
          "type": "text",
          "text": "Created from Jenkins Pipeline"
        }]
      }]
    }
  }
}
EOF

curl -X POST \
-u "$JIRA_EMAIL:$JIRA_TOKEN" \
-H "Accept: application/json" \
-H "Content-Type: application/json" \
--data @issue.json \
"$JIRA_URL/rest/api/3/issue"
"""
            }
        }
    }
}

        stage('Deploy to DEV') {
            when { expression { env.DEPLOY_TO == 'dev' } }

            steps {
                script {
                    withAWS(
                        region: "${region}",
                        credentials: 'aws-creds'
                    ) {
                        sh """
                            aws eks update-kubeconfig \
                                --region ${region} \
                                --name ${CLUSTER}

                            cd helm

                            sed -i "s/IMAGE_VERSION/${SHORT_COMMIT}/g" values.yaml

                            helm upgrade --install ${component} \
                                -f values-dev.yaml \
                                -n ${project}-dev \
                                --atomic \
                                --wait \
                                --timeout=5m .
                        """
                    }
                }
            }
        }

        stage('Functional Tests') {
            when { expression { env.DEPLOY_TO == 'dev' } }

            steps {
                script {

                    def result = build(
                        job: "${project}/${component}-tests",
                        wait: true,
                        propagate: false
                    )

                    if (result.result != 'SUCCESS') {
                        error("Functional tests failed.")
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // UAT FLOW
        // ─────────────────────────────────────────────

        stage('Deploy to UAT') {
            when { expression { env.DEPLOY_TO == 'uat' } }

            steps {
                script {

                    sh "git checkout ${env.TARGET_VERSION}"

                    withAWS(
                        region: "${region}",
                        credentials: 'aws-creds'
                    ) {
                        sh """
                            aws eks update-kubeconfig \
                                --region ${region} \
                                --name ${CLUSTER}

                            cd helm

                            sed -i "s/IMAGE_VERSION/${env.TARGET_VERSION}/g" values.yaml

                            helm upgrade --install ${component} \
                                -f values-uat.yaml \
                                -n ${project}-uat \
                                --atomic \
                                --wait \
                                --timeout=5m .
                        """
                    }
                }
            }
        }

        // ─────────────────────────────────────────────
        // PROD FLOW
        // ─────────────────────────────────────────────

        stage('Validate Change Request') {
            when { expression { env.DEPLOY_TO == 'prod' } }

            steps {
                script {
                    utils.validateChangeRequest(
                        env.CR_NUMBER
                    )
                }
            }
        }

        stage('Deploy to PROD') {
            when { expression { env.DEPLOY_TO == 'prod' } }

            steps {
                script {

                    sh "git checkout ${env.TARGET_VERSION}"

                    withAWS(
                        region: "${region}",
                        credentials: 'aws-creds'
                    ) {
                        sh """
                            aws eks update-kubeconfig \
                                --region ${region} \
                                --name ${CLUSTER}

                            cd helm

                            sed -i "s/IMAGE_VERSION/${env.TARGET_VERSION}/g" values.yaml

                            helm upgrade --install ${component} \
                                -f values-prod.yaml \
                                -n ${project}-prod \
                                --atomic \
                                --wait \
                                --timeout=5m .
                        """
                    }

                    utils.transitionJiraTicket(
                        env.JIRA_ISSUE,
                        'Done'
                    )

                    withCredentials([
                        string(
                            credentialsId: 'github-token',
                            variable: 'GITHUB_TOKEN'
                        )
                    ]) {

                        sh '''
                            APP_VERSION=$(cat package.json | jq -r '.version')

                            REPO_PATH=$(git remote get-url origin | sed 's/.*github\\.com[\\/:]//;s/\\.git$//')

                            git remote set-url origin https://$GITHUB_TOKEN@github.com/$REPO_PATH

                            git tag $APP_VERSION

                            git push origin $APP_VERSION
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo "${env.DEPLOY_TO} deploy succeeded for ${component}"
        }

        failure {
            echo "${env.DEPLOY_TO} deploy failed for ${component}"
        }
    }
}

}
