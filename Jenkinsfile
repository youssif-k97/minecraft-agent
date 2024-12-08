pipeline {
    agent any

    parameters {
        string(name: 'APP_NAME', defaultValue: 'minecraft-agent', description: 'Application name used for Docker image')
        choice(name: 'DEPLOY_ENV', choices: ['hetzner', 'dev', 'staging', 'prod'], description: 'Deployment environment')
    }

    environment {
        // Get Docker credentials from Jenkins credentials store
        DOCKER_CREDENTIALS = 'docker-hub-credentials'
        DOCKER_CREDENTIALS_NEW = credentials('docker-hub-credentials')


        // Construct Docker image name and tag
        GIT_COMMIT_SHORT = powershell(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        VERSION = powershell(script: 'if (Test-Path VERSION) { Get-Content VERSION } else { "1.0.0" }', returnStdout: true).trim()
        DOCKER_IMAGE = "${params.APP_NAME}"
        DOCKER_TAG = "${params.DEPLOY_ENV}-${VERSION}-${GIT_COMMIT_SHORT}"
        DOCKER_LATEST = "${DOCKER_IMAGE}:${params.DEPLOY_ENV}-latest"
        DOCKER_VERSIONED = "${DOCKER_IMAGE}:${DOCKER_TAG}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build Spring Boot App') {
            steps {
                bat 'mvnw.cmd clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    def workspace = env.WORKSPACE.replace('\\', '/')
                    withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS, usernameVariable: 'DOCKER_CREDENTIALS_USR', passwordVariable: 'DOCKER_CREDENTIALS_PSW')]) {
                        powershell """
                        docker build -t ${DOCKER_CREDENTIALS_USR}/${DOCKER_VERSIONED} `
                            --build-arg APP_VERSION=${VERSION} `
                            --build-arg BUILD_ENV=${params.DEPLOY_ENV} `
                            .
                        docker tag ${DOCKER_CREDENTIALS_USR}/${DOCKER_VERSIONED} ${DOCKER_CREDENTIALS_USR}/${DOCKER_LATEST}
                        """
                    }
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: DOCKER_CREDENTIALS, usernameVariable: 'DOCKER_CREDENTIALS_USR', passwordVariable: 'DOCKER_CREDENTIALS_PSW')]) {
                        powershell """
                            docker login -u ${DOCKER_CREDENTIALS_USR} -p ${DOCKER_CREDENTIALS_PSW}
                            docker push ${DOCKER_CREDENTIALS_USR}/${DOCKER_VERSIONED}
                            docker push ${DOCKER_CREDENTIALS_USR}/${DOCKER_LATEST}
                        """
                    }
                }
            }
        }

        stage('Deploy to Server') {
            steps {
                script {
                    def deployConfig = load "deploy/dev/config.groovy"

                    // Create temporary deployment files
                    def tempDir = pwd(tmp: true)
                    def envFile = "${tempDir}/env.txt"
                    def composeFile = "${tempDir}/docker-compose.yml"
                    powershell """
                        Set-Content -Path "${envFile}" -Value @"
DOCKER_IMAGE=${DOCKER_CREDENTIALS_NEW_USR}/${DOCKER_VERSIONED}
APP_NAME=${params.APP_NAME}
SPRING_PROFILE=${params.DEPLOY_ENV}
SERVER_PROPERTIES_PATH=/opt/mscs/worlds/world1/server.properties
MINECRAFT_MSCS_PATH=/usr/local/bin/mscs
"@
                        Copy-Item "deploy/docker-compose.yml" -Destination "${composeFile}"
                    """
                    // Create a temporary directory for deployment files
                    withCredentials([sshUserPrivateKey(credentialsId: 'hetzner-ssh-key',
                                                                         keyFileVariable: 'SSH_KEY',
                                                                         usernameVariable: 'SSH_USER')]) {
                        powershell """
                            # Get current user
                            \$currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name

                            # Fix permissions
                            icacls "${SSH_KEY}" /inheritance:r
                            icacls "${SSH_KEY}" /grant:r "\${currentUser}:(F)"

                            # Create temp directory for deployment files
                            ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no ${SSH_USER}@${deployConfig.DOMAIN_NAME} 'mkdir -p /tmp/deploy-${BUILD_NUMBER}'

                            # Copy files using scp (Windows compatible)
                            scp -i ${SSH_KEY} -o StrictHostKeyChecking=no "${envFile}" ${SSH_USER}@${deployConfig.DOMAIN_NAME}:/tmp/deploy-${BUILD_NUMBER}/.env
                            scp -i ${SSH_KEY} -o StrictHostKeyChecking=no "${composeFile}" ${SSH_USER}@${deployConfig.DOMAIN_NAME}:/tmp/deploy-${BUILD_NUMBER}/docker-compose.yml
                            scp -i ${SSH_KEY} -r -o StrictHostKeyChecking=no "deploy/nginx" ${SSH_USER}@${deployConfig.DOMAIN_NAME}:/tmp/deploy-${BUILD_NUMBER}/

                            ssh -i ${SSH_KEY} -o StrictHostKeyChecking=no ${SSH_USER}@${deployConfig.DOMAIN_NAME} @'
sudo mkdir -p /opt/minecraft-agent
sudo cp /tmp/deploy-${BUILD_NUMBER}/.env /opt/minecraft-agent/
sudo cp /tmp/deploy-${BUILD_NUMBER}/docker-compose.yml /opt/minecraft-agent/docker-compose.yml
sudo cp /tmp/deploy-${BUILD_NUMBER}/nginx/minecraft-agent.conf /etc/nginx/conf.d/
docker network create web || true
cd /opt/minecraft-agent
docker-compose pull
sudo systemctl daemon-reload
sudo systemctl enable minecraft-server
sudo systemctl restart minecraft-server
sudo systemctl restart nginx
rm -rf /tmp/deploy-${BUILD_NUMBER}
timeout 60 bash -c \\\"while ! curl -s http://localhost:8080/actuator/health | grep UP; do sleep 5; done\\\"
'@
                        """
                    }
                }
            }
        }
    }

        post {
            always {
                cleanWs()
                powershell 'docker logout ${DOCKER_REGISTRY}'
            }
        }
}