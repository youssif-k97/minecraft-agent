pipeline {
    agent any

    parameters {
        string(name: 'APP_NAME', defaultValue: 'minecraft-agent', description: 'Application name used for Docker image')
        choice(name: 'DEPLOY_ENV', choices: ['hetzner', 'dev', 'staging', 'prod'], description: 'Deployment environment')
    }

    environment {
        // Get Docker credentials from Jenkins credentials store
        DOCKER_CREDENTIALS = credentials('docker-hub-credentials')
        DOCKER_REGISTRY = credentials('docker-registry-url') // e.g., 'docker.io/username' or private registry

        // Get server details from Jenkins credentials
        DEPLOY_SERVER = credentials("${params.DEPLOY_ENV}-ssh-key")

        // Construct Docker image name and tag
        GIT_COMMIT_SHORT = powershell(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
        VERSION = powershell(script: 'if (Test-Path VERSION) { Get-Content VERSION } else { "1.0.0" }', returnStdout: true).trim()
        DOCKER_IMAGE = "${DOCKER_REGISTRY}/${params.APP_NAME}"
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
                powershell 'mvn clean package -DskipTests'
            }
        }

        stage('Build Docker Image') {
            steps {
                script {
                    def workspace = env.WORKSPACE.replace('\\', '/')
                    powershell """
                        docker build -t ${DOCKER_VERSIONED} `
                            --build-arg APP_VERSION=${VERSION} `
                            --build-arg BUILD_ENV=${params.DEPLOY_ENV} `
                            .
                        docker tag ${DOCKER_VERSIONED} ${DOCKER_LATEST}
                    """
                }
            }
        }

        stage('Push Docker Image') {
            steps {
                script {
                    powershell """
                        echo ${DOCKER_CREDENTIALS_PSW} | docker login ${DOCKER_REGISTRY} -u ${DOCKER_CREDENTIALS_USR} --password-stdin
                        docker push ${DOCKER_VERSIONED}
                        docker push ${DOCKER_LATEST}
                    """
                }
            }
        }

        stage('Deploy to Server') {
            steps {
                script {
                    def deployConfig = load "deploy/${params.DEPLOY_ENV}/config.groovy"

                    // Create temporary deployment files
                    def tempDir = pwd(tmp: true)
                    def envFile = "${tempDir}/env.txt"
                    def composeFile = "${tempDir}/docker-compose.yml"
                    powershell """
                        Set-Content -Path "${envFile}" -Value @"
                        DOCKER_IMAGE=${DOCKER_VERSIONED}
                        APP_NAME=${params.APP_NAME}
                        SPRING_PROFILE=${params.DEPLOY_ENV}
                        "@
                        Copy-Item "deploy/docker-compose.yml" -Destination "${composeFile}"
                    """
                    // Create a temporary directory for deployment files
                    sshagent([DEPLOY_SERVER]) {
                        powershell """
                            # Create temp directory for deployment files
                            ssh -o StrictHostKeyChecking=no ${DEPLOY_SERVER_USR}@${DEPLOY_SERVER_PSW} 'mkdir -p /tmp/deploy-${BUILD_NUMBER}'

                            # Copy files using scp (Windows compatible)
                            scp -o StrictHostKeyChecking=no "${envFile}" ${DEPLOY_SERVER_USR}@${DEPLOY_SERVER_PSW}:/tmp/deploy-${BUILD_NUMBER}/.env
                            scp -o StrictHostKeyChecking=no "${composeFile}" ${DEPLOY_SERVER_USR}@${DEPLOY_SERVER_PSW}:/tmp/deploy-${BUILD_NUMBER}/docker-compose.yml
                            scp -r -o StrictHostKeyChecking=no "deploy/nginx" ${DEPLOY_SERVER_USR}@${DEPLOY_SERVER_PSW}:/tmp/deploy-${BUILD_NUMBER}/

                            ssh -o StrictHostKeyChecking=no ${DEPLOY_SERVER_USR}@${DEPLOY_SERVER_PSW} '
                                sudo mkdir -p /opt/minecraft-agent
                                sudo cp /tmp/deploy-${BUILD_NUMBER}/.env /opt/minecraft-agent/

                                sudo cp /tmp/deploy-${BUILD_NUMBER}/docker-compose.yml /opt/minecraft-agent/docker-compose.yml

                                sudo cp /tmp/deploy-${BUILD_NUMBER}/nginx/minecraft-agent.conf /etc/nginx/conf.d/

                                docker network create web || true

                                sudo systemctl daemon-reload
                                sudo systemctl enable minecraft-server
                                sudo systemctl restart minecraft-server
                                sudo systemctl restart nginx

                                timeout 60 bash -c "until curl -s http://localhost:8080/actuator/health | grep UP; do sleep 5; done"
                            '
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